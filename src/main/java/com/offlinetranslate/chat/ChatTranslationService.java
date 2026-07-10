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
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ChatIconManager;

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
		if (!config.autoDetect() || !TRANSLATABLE_TYPES.contains(event.getType()))
		{
			return;
		}

		String localName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (localName != null && localName.equalsIgnoreCase(net.runelite.client.util.Text.removeTags(event.getName())))
		{
			return;
		}

		String message = event.getMessage();
		String sender = event.getName();
		MessageNode messageNode = event.getMessageNode();

		detectionExecutor.submit(() -> handleMessage(sender, message, messageNode));
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

		if (detected == null || detected == preferred)
		{
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
			panel.logTranslatedMessage(new TranslatedMessageEntry(sender, message, translated, detected));
		}
		catch (TranslationException e)
		{
			System.err.println("[Offline Translate] Translation failed for message from " + sender + ":");
			e.printStackTrace();
		}
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
