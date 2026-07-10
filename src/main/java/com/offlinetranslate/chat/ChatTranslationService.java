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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
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
	 * Registered as a persistent player right-click option via {@code MenuManager}, the same
	 * mechanism "Add friend"/"Report" use - it appears both on world player right-clicks and on
	 * chat name right-clicks, since OSRS handles both through the same underlying player-option
	 * system. Public so {@link com.offlinetranslate.OfflineTranslatePlugin} can register/
	 * deregister it without duplicating the literal string.
	 */
	public static final String RIGHT_CLICK_OPTION = "Translate";
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

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER || !RIGHT_CLICK_OPTION.equals(event.getMenuOption()))
		{
			return;
		}

		String playerName = normalizePlayerName(event.getMenuTarget());
		String message = lastMessageByPlayer.get(playerName);
		if (message == null)
		{
			warn("No recent message from " + playerName + " to translate.");
			return;
		}

		detectionExecutor.submit(() -> handleManualTranslate(playerName, message));
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
			panel.logTranslatedMessage(new TranslatedMessageEntry(sender, message, translated, detected));
		}
		catch (TranslationException e)
		{
			System.err.println("[Offline Translate] Manual translate failed for " + sender + ":");
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
			panel.logTranslatedMessage(new TranslatedMessageEntry(sender, message, translated, detected));
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
