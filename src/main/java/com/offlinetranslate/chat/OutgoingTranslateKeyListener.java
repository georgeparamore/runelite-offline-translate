package com.offlinetranslate.chat;

import com.offlinetranslate.Language;
import com.offlinetranslate.OfflineTranslateConfig;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import com.offlinetranslate.translate.TranslationEngine;
import com.offlinetranslate.translate.TranslationException;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

/**
 * Rewrites what you typed before it's sent, when it starts with the configured translate
 * command (default {@code /t }): translates the rest of the line from your preferred language
 * into the current output language, in place, so what other players actually receive is the
 * translated text.
 * <p>
 * <b>Status: experimental, unverified in a live client.</b> This works by mutating
 * {@link VarClientStr#CHATBOX_TYPED_TEXT} inside {@code keyPressed} for the Enter key, on the
 * assumption that RuneLite's key-listener chain runs before the game's own script-driven
 * "send chat message" handling reads that same variable for the same keystroke - which is how
 * other plugins that alter outgoing text are believed to work, but this hasn't been confirmed
 * against a running client here. Translation runs synchronously on the EDT (a few hundred ms
 * for a short line), which is a deliberate simplicity tradeoff, not an oversight - deferring it
 * asynchronously isn't an option since the var has to be mutated before this same keystroke's
 * send logic runs.
 * <p>
 * On failure (e.g. the needed language pack isn't downloaded), this deliberately does *not*
 * try to block the send via {@code event.consume()} - whether consuming actually prevents the
 * vanilla client from still sending is unverified, and getting that wrong would be worse than
 * the fallback here: strip the command prefix and send your original, untranslated text instead
 * of risking a garbled or duplicate send.
 */
@Slf4j
@Singleton
public class OutgoingTranslateKeyListener implements KeyListener
{
	private final Client client;
	private final ClientThread clientThread;
	private final OfflineTranslateConfig config;
	private final TranslationEngine translationEngine;
	private final ModelManager modelManager;

	@Inject
	public OutgoingTranslateKeyListener(Client client, ClientThread clientThread, OfflineTranslateConfig config, TranslationEngine translationEngine, ModelManager modelManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.translationEngine = translationEngine;
		this.modelManager = modelManager;
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.getKeyCode() != KeyEvent.VK_ENTER)
		{
			return;
		}

		String prefix = config.translateCommand();
		if (prefix == null || prefix.isEmpty())
		{
			return;
		}

		String typed = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (typed == null || !typed.startsWith(prefix))
		{
			return;
		}

		String argument = typed.substring(prefix.length()).trim();
		if (argument.isEmpty())
		{
			return;
		}

		Language source = config.preferredLanguage();
		Language target = config.outputLanguage();

		if (!packsReady(source, target))
		{
			warn("Missing language pack for " + source.getDisplayName() + " -> " + target.getDisplayName() + " - sending untranslated.");
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, argument);
			return;
		}

		try
		{
			String translated = translationEngine.translate(argument, source, target);
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, translated);
		}
		catch (TranslationException ex)
		{
			log.warn("Outgoing translation failed", ex);
			warn("Translation failed - sending untranslated.");
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, argument);
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	private boolean packsReady(Language source, Language target)
	{
		boolean sourceReady = source.isEnglish() || modelManager.getStatus(source, PackDirection.TO_ENGLISH) == PackStatus.READY;
		boolean targetReady = target.isEnglish() || modelManager.getStatus(target, PackDirection.FROM_ENGLISH) == PackStatus.READY;
		return sourceReady && targetReady;
	}

	private void warn(String message)
	{
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", "[Offline Translate] " + message, null));
	}
}
