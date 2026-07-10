package com.offlinetranslate.chat;

import com.offlinetranslate.Language;
import com.offlinetranslate.OfflineTranslateConfig;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import com.offlinetranslate.translate.ChatLanguageDetector;
import com.offlinetranslate.translate.TranslationEngine;
import com.offlinetranslate.translate.TranslationException;
import com.offlinetranslate.ui.OfflineTranslatePanel;
import com.offlinetranslate.ui.TranslatedMessageEntry;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.Point;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.util.Text;

/**
 * Detects the language of incoming chat and, where possible, translates it - flagging the
 * sender's name with a language badge and logging a translation to the side panel. Detection
 * runs on a background executor since neither language ID nor ONNX inference should block the
 * client thread; only the final UI/chat mutation hops back via {@link ClientThread}.
 */
@Slf4j
@Singleton
public class ChatTranslationService
{
	private static final Set<ChatMessageType> TRANSLATABLE_TYPES = EnumSet.of(
		ChatMessageType.PUBLICCHAT,
		ChatMessageType.MODCHAT,
		ChatMessageType.PRIVATECHAT,
		ChatMessageType.FRIENDSCHAT,
		ChatMessageType.CLAN_CHAT,
		ChatMessageType.CLAN_GUEST_CHAT
	);

	/**
	 * Added directly to chat line widgets via {@link #onMenuOpened}, rather than through
	 * {@code MenuManager.addPlayerMenuItem()} (the original implementation, which only ever
	 * appeared on the 3D player model / chat *name* click, both driven by the same underlying
	 * player-option system). Checking the hovered chat line's own widget bounds instead means
	 * this shows up right-clicking anywhere on the line - the name or the message text - inside
	 * the actual chatbox, which is what was asked for.
	 */
	static final String RIGHT_CLICK_OPTION = "Translate";
	private static final int MAX_TRACKED_PLAYERS = 200;

	private final Client client;
	private final ClientThread clientThread;
	private final OfflineTranslateConfig config;
	private final ChatLanguageDetector languageDetector;
	private final TranslationEngine translationEngine;
	private final ModelManager modelManager;
	private final ChatIconManager chatIconManager;
	private final OfflineTranslatePanel panel;

	private final ExecutorService detectionExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "offline-translate-detect");
		t.setDaemon(true);
		return t;
	});
	private final Map<Language, Integer> flagIconIds = new ConcurrentHashMap<>();
	private final Set<Language> promptedThisSession = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// Bounded, insertion-order-evicting: tracks each player's most recent message so the
	// right-click "Translate" option (which fires independently of auto-detect, and doesn't
	// carry the message with it - only the player's name) has something to translate.
	private final Map<String, String> lastMessageByPlayer = java.util.Collections.synchronizedMap(
		new java.util.LinkedHashMap<String, String>(16, 0.75f, false)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> eldest)
			{
				return size() > MAX_TRACKED_PLAYERS;
			}
		});

	@Inject
	public ChatTranslationService(
		Client client,
		ClientThread clientThread,
		OfflineTranslateConfig config,
		ChatLanguageDetector languageDetector,
		TranslationEngine translationEngine,
		ModelManager modelManager,
		ChatIconManager chatIconManager,
		OfflineTranslatePanel panel)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.languageDetector = languageDetector;
		this.translationEngine = translationEngine;
		this.modelManager = modelManager;
		this.chatIconManager = chatIconManager;
		this.panel = panel;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		System.err.println("[Offline Translate] onChatMessage: type=" + event.getType() + " name=" + event.getName() + " message=\"" + event.getMessage() + "\" autoDetect=" + config.autoDetect());
		if (!TRANSLATABLE_TYPES.contains(event.getType()))
		{
			System.err.println("[Offline Translate] skipped: type not translatable");
			return;
		}

		String localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (localName != null && localName.equalsIgnoreCase(normalizePlayerName(event.getName())))
		{
			System.err.println("[Offline Translate] skipped: message is from local player");
			return;
		}

		String message = event.getMessage();
		String sender = event.getName();
		MessageNode messageNode = event.getMessageNode();

		// Tracked unconditionally (not gated on autoDetect) so the right-click "Translate"
		// option still has something to work with even if auto-detect is turned off.
		lastMessageByPlayer.put(normalizePlayerName(sender), message);

		if (!config.autoDetect())
		{
			System.err.println("[Offline Translate] skipped auto-detect: autoDetect off");
			return;
		}

		detectionExecutor.submit(() -> handleMessage(sender, message, messageNode));
	}

	/**
	 * Rewritten from an earlier {@code MenuEntryAdded}-based version that piggybacked on
	 * whatever default option (e.g. "Report") the game already added for the hovered chat line -
	 * confirmed live to never show "Translate" at all, meaning that assumption was wrong
	 * somewhere (either the game doesn't add a default option for ordinary chat lines the way
	 * player-model right-clicks do, or chat lines don't belong to the widget group that
	 * implementation assumed - {@code MenuEntryAdded} firing zero times gave no way to tell
	 * which). This version doesn't depend on the game adding anything: {@code MenuOpened} fires
	 * once whenever a right-click menu is about to display, so this checks the mouse position
	 * directly against every chat line widget's bounds and adds "Translate" unconditionally when
	 * the cursor is over one - independent of whether OSRS itself offered any options there.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		Widget container = client.getWidget(WidgetInfo.CHATBOX_MESSAGE_LINES);
		if (container == null)
		{
			return;
		}

		Widget[] children = container.getChildren();
		if (children == null)
		{
			System.err.println("[Offline Translate] chatbox message-lines widget " + container.getId() + " has no children");
			return;
		}

		Point mouse = client.getMouseCanvasPosition();
		for (Widget child : children)
		{
			if (child == null || child.isHidden())
			{
				continue;
			}
			String widgetText = child.getText();
			if (widgetText == null || widgetText.isEmpty())
			{
				continue;
			}
			if (!child.getBounds().contains(mouse.getX(), mouse.getY()))
			{
				continue;
			}

			System.err.println("[Offline Translate] chat line under cursor: widgetId=" + child.getId() + " text=\"" + widgetText + "\"");

			ChatLineMatch match = findChatLine(widgetText);
			if (match == null)
			{
				System.err.println("[Offline Translate] chat line under cursor but no matching MessageNode found: text=\"" + widgetText + "\"");
				return;
			}
			if (!TRANSLATABLE_TYPES.contains(match.type))
			{
				return;
			}

			String localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
			if (localName != null && localName.equalsIgnoreCase(match.sender))
			{
				return;
			}

			String sender = match.sender;
			String message = match.message;
			lastMessageByPlayer.put(sender, message);

			client.createMenuEntry(-1)
				.setOption(RIGHT_CLICK_OPTION)
				.setTarget(match.sender)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> {
					System.err.println("[Offline Translate] chat line \"Translate\" clicked: sender=" + sender + " message=\"" + message + "\"");
					detectionExecutor.submit(() -> handleManualTranslate(sender, message));
				});
			return;
		}
	}

	/**
	 * Correlates the hovered chat line widget back to the live {@link MessageNode} it renders,
	 * by matching rendered text content rather than by index - OSRS doesn't expose name/message
	 * as separate widgets per line (it's one text widget for the whole line, with the game's own
	 * script deciding internally whether a click landed on the name portion), and matching by
	 * widget child index against {@link Client#getChatLineMap()} would require assumptions about
	 * cross-channel line ordering for the combined "All" chat view that aren't safe to guess at
	 * blind. Matching by content sidesteps that: whatever text is actually showing is compared
	 * against every currently-buffered message, tag-stripped on both sides.
	 */
	private ChatLineMatch findChatLine(String widgetText)
	{
		String stripped = Text.removeTags(widgetText).trim();
		for (ChatLineBuffer buffer : client.getChatLineMap().values())
		{
			for (MessageNode node : buffer.getLines())
			{
				if (node == null || node.getValue() == null)
				{
					continue;
				}
				String name = node.getName();
				String candidate = (name == null || name.isEmpty())
					? Text.removeTags(node.getValue())
					: Text.removeTags(name) + ": " + Text.removeTags(node.getValue());
				if (candidate.trim().equals(stripped))
				{
					return new ChatLineMatch(normalizePlayerName(name), node.getValue(), node.getType());
				}
			}
		}
		return null;
	}

	private static final class ChatLineMatch
	{
		final String sender;
		final String message;
		final ChatMessageType type;

		ChatLineMatch(String sender, String message, ChatMessageType type)
		{
			this.sender = sender;
			this.message = message;
			this.type = type;
		}
	}

	private void handleManualTranslate(String sender, String message)
	{
		Language preferred = config.preferredLanguage();
		Language detected;
		try
		{
			detected = languageDetector.detect(message);
		}
		catch (RuntimeException e)
		{
			System.err.println("[Offline Translate] Manual translate: detection failed:");
			e.printStackTrace();
			return;
		}

		System.err.println("[Offline Translate] Manual translate: detected=" + detected + " preferred=" + preferred);
		if (detected == null)
		{
			warn("Couldn't confidently detect the language of " + sender + "'s last message.");
			return;
		}
		if (detected == preferred)
		{
			warn(sender + "'s last message already looks like it's in your preferred language.");
			return;
		}

		PackStatus toEnglishStatus = detected.isEnglish() ? PackStatus.READY : modelManager.getStatus(detected, PackDirection.TO_ENGLISH);
		System.err.println("[Offline Translate] Manual translate: toEnglishStatus=" + toEnglishStatus);
		if (toEnglishStatus != PackStatus.READY)
		{
			if (config.promptToDownloadMissingPacks() && promptedThisSession.add(detected))
			{
				panel.promptDownloadMissingPack(detected, PackDirection.TO_ENGLISH);
			}
			else
			{
				warn("Missing the " + detected.getDisplayName() + " language pack - download it from the side panel first.");
			}
			return;
		}

		try
		{
			String translated = translationEngine.translate(message, detected, preferred);
			System.err.println("[Offline Translate] Manual translate: logging to panel: sender=" + sender + " translated=\"" + translated + "\"");
			panel.logTranslatedMessage(new TranslatedMessageEntry(sender, message, translated, detected));
		}
		catch (TranslationException e)
		{
			System.err.println("[Offline Translate] Manual translate failed for " + sender + ":");
			e.printStackTrace();
		}
		catch (RuntimeException e)
		{
			// Broader than the TranslationException catch above on purpose: this task runs via
			// ExecutorService.submit(Runnable), which silently discards any exception thrown out
			// of the task with zero console output (the Future holding it is never inspected) -
			// confirmed as a real gap after a live test showed detection succeeding and then
			// nothing at all, with no way to tell whether that meant "succeeded with no log
			// line" or "threw something unexpected and vanished". This makes that failure mode
			// visible instead of silent.
			System.err.println("[Offline Translate] Manual translate: unexpected error for " + sender + ":");
			e.printStackTrace();
		}
	}

	/** Strips formatting tags and the " (level-N)" suffix RuneLite appends to player right-click targets, so names from chat events and right-click events compare equal. */
	private static String normalizePlayerName(String rawName)
	{
		if (rawName == null)
		{
			return null;
		}
		return Text.removeTags(rawName).replaceAll("\\s*\\(level-\\d+\\)\\s*$", "").trim();
	}

	private void handleMessage(String sender, String message, MessageNode messageNode)
	{
		Language preferred = config.preferredLanguage();
		Language detected;
		try
		{
			detected = languageDetector.detect(message);
		}
		catch (RuntimeException e)
		{
			// See OutgoingTranslateKeyListener for why this is printStackTrace() and not
			// log.debug(): SLF4J was observed defaulting to a no-op logger under the plain
			// JavaExec launch used by ./gradlew runOfflineTranslate.
			System.err.println("[Offline Translate] Language detection failed:");
			e.printStackTrace();
			return;
		}

		System.err.println("[Offline Translate] detected=" + detected + " preferred=" + preferred);
		if (detected == null || detected == preferred)
		{
			System.err.println("[Offline Translate] skipped: nothing detected or same as preferred");
			return;
		}

		if (config.showFlagsInChat())
		{
			flagMessage(sender, messageNode, detected);
		}

		if (config.autoUpdateOutputLanguage())
		{
			panel.setDetectedOutputLanguage(detected);
		}

		PackStatus toEnglishStatus = detected.isEnglish() ? PackStatus.READY : modelManager.getStatus(detected, PackDirection.TO_ENGLISH);
		System.err.println("[Offline Translate] toEnglishStatus=" + toEnglishStatus);
		if (toEnglishStatus != PackStatus.READY)
		{
			if (config.promptToDownloadMissingPacks() && promptedThisSession.add(detected))
			{
				panel.promptDownloadMissingPack(detected, PackDirection.TO_ENGLISH);
			}
			return;
		}

		try
		{
			String translated = translationEngine.translate(message, detected, preferred);
			System.err.println("[Offline Translate] logging to panel: sender=" + sender + " translated=\"" + translated + "\"");
			// normalizePlayerName(), not the raw sender: the side panel is a plain Swing label,
			// not the in-game chat widget, so it can't render OSRS's <img=N>/<col=...> chat tags
			// - left raw, entries showed literal text like "<img=10>Optimism" instead of a clean
			// name.
			panel.logTranslatedMessage(new TranslatedMessageEntry(normalizePlayerName(sender), message, translated, detected));
		}
		catch (TranslationException e)
		{
			System.err.println("[Offline Translate] Translation failed for message from " + sender + ":");
			e.printStackTrace();
		}
	}

	private void warn(String message)
	{
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", "[Offline Translate] " + message, null));
	}

	private void flagMessage(String sender, MessageNode messageNode, Language detected)
	{
		int iconId = flagIconIds.computeIfAbsent(detected, this::registerFlagIcon);
		if (iconId < 0)
		{
			return;
		}
		int spriteIndex = chatIconManager.chatIconIndex(iconId);
		if (spriteIndex < 0)
		{
			return;
		}

		clientThread.invoke(() -> {
			String currentName = messageNode.getName();
			String badge = "<img=" + spriteIndex + ">";
			if (!currentName.startsWith(badge))
			{
				messageNode.setName(badge + currentName);
				client.refreshChat();
			}
		});
	}

	private int registerFlagIcon(Language language)
	{
		// clientThread.invoke() only runs inline when already called from the client thread;
		// from this background executor it just enqueues for later, so a plain shared-variable
		// write-then-read here would race. A future makes the cross-thread handoff correct.
		BufferedImage badge = FlagIconFactory.create(language);
		java.util.concurrent.CompletableFuture<Integer> result = new java.util.concurrent.CompletableFuture<>();
		clientThread.invoke(() -> result.complete(chatIconManager.registerChatIcon(badge)));
		try
		{
			return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
		}
		catch (Exception e)
		{
			log.warn("Failed to register flag icon for {}", language, e);
			return -1;
		}
	}

	public void shutdown()
	{
		detectionExecutor.shutdownNow();
	}
}
