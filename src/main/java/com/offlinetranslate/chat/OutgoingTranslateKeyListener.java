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
 * Translates whatever you've currently typed in the chatbox, in place, when you press the
 * configured hotkey (default Ctrl+T) - leaving it sitting in the input box for you to send
 * yourself with a completely normal, untouched Enter press afterward.
 * <p>
 * <b>Why a separate hotkey instead of intercepting Enter:</b> an earlier version of this tried
 * to rewrite {@link VarClientStr#CHATBOX_TYPED_TEXT} inside the Enter keypress handler itself,
 * on the assumption that RuneLite's key-listener chain runs before the game's own script-driven
 * send-message handling reads that same variable for the same keystroke. That was tested live
 * and confirmed wrong: it corrupted the chatbox's send state outright (Enter would cycle
 * between the "Press Enter to Chat" placeholder and the typed text, never actually sending -
 * for *any* message, not just translated ones, while stuck). {@code setVarcStrValue} on this
 * var is a legitimate, working mechanism - other RuneLite plugins rewrite it successfully (live
 * swear filters, auto-capitalization) - but every one of those does it progressively as you
 * type, never during the same keystroke that triggers a send. Decoupling translate (this
 * hotkey) from send (an untouched Enter) follows that same working pattern instead of fighting
 * it, and was the fix once the actual failure mode was understood rather than guessed at.
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
		if (e.getModifiersEx() != 0)
		{
			// Temporary diagnostic: print every modified keypress so we can see exactly what
			// key code/modifiers actually arrive here vs. what the configured hotkey expects,
			// rather than guessing why a combo doesn't match.
			System.err.println("[Offline Translate] keyPressed: keyCode=" + e.getExtendedKeyCode()
				+ " modifiersEx=" + e.getModifiersEx() + " (" + java.awt.event.InputEvent.getModifiersExText(e.getModifiersEx()) + ")"
				+ " | configured hotkey=" + config.translateHotkey() + " matches=" + config.translateHotkey().matches(e));
		}

		if (!config.translateHotkey().matches(e))
		{
			return;
		}
		// Consume so the hotkey combo (e.g. Ctrl+T) can't also register as ordinary chat input;
		// harmless either way since Ctrl-modified keys don't normally produce printable chat
		// text, but this makes that explicit rather than assumed.
		e.consume();

		String typed = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		System.err.println("[Offline Translate] hotkey fired, typed=\"" + typed + "\"");
		if (typed == null || typed.trim().isEmpty())
		{
			System.err.println("[Offline Translate] nothing typed, doing nothing");
			return;
		}

		Language source = config.preferredLanguage();
		Language target = config.outputLanguage();
		System.err.println("[Offline Translate] source=" + source + " target=" + target);

		if (!packsReady(source, target))
		{
			System.err.println("[Offline Translate] packs not ready, aborting");
			warn("Missing language pack for " + source.getDisplayName() + " -> " + target.getDisplayName() + " - nothing to translate yet.");
			return;
		}

		// Only ever call the blocking translate() below once both translators are already
		// loaded in memory - first-time loading (ONNX sessions + native SentencePiece library)
		// takes seconds. Since this hotkey isn't tied to sending, there's no urgency: leave the
		// typed text untouched and warm up in the background instead of translating it now.
		boolean sourceWarm = source.isEnglish() || translationEngine.isWarm(source, PackDirection.TO_ENGLISH);
		boolean targetWarm = target.isEnglish() || translationEngine.isWarm(target, PackDirection.FROM_ENGLISH);
		System.err.println("[Offline Translate] sourceWarm=" + sourceWarm + " targetWarm=" + targetWarm);
		if (!sourceWarm || !targetWarm)
		{
			translationEngine.warmUp(source, PackDirection.TO_ENGLISH);
			translationEngine.warmUp(target, PackDirection.FROM_ENGLISH);
			warn("Loading the translator for the first time - try the hotkey again in a few seconds.");
			return;
		}

		try
		{
			String translated = translationEngine.translate(typed, source, target);
			System.err.println("[Offline Translate] translated \"" + typed + "\" -> \"" + translated + "\"");
			// Writing the var alone (from this thread, without a follow-up redraw signal) was
			// confirmed live to leave the on-screen chatbox showing the old text even though
			// the underlying value presumably changed - the input line likely doesn't
			// repaint from CHATBOX_TYPED_TEXT on its own the way a normal keystroke's redraw
			// does. Matching the same pattern already confirmed working for chat message
			// flagging: mutate on the client thread and follow with refreshChat().
			clientThread.invoke(() -> {
				client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, translated);
				client.refreshChat();
			});
		}
		catch (TranslationException ex)
		{
			// printStackTrace(), not log.warn(): SLF4J has been observed falling back to a
			// no-op logger under the plain JavaExec launch (RuneLite's own bootstrap normally
			// wires logback; running via ./gradlew runOfflineTranslate skips that), which was
			// silently swallowing exactly this diagnostic. This bypasses the logging framework
			// entirely so it's visible in the terminal regardless.
			System.err.println("[Offline Translate] Outgoing translation failed:");
			ex.printStackTrace();
			warn("Translation failed - see console for details.");
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
