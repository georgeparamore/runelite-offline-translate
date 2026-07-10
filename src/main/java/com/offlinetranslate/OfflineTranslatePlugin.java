package com.offlinetranslate;

import com.google.inject.Provides;
import com.offlinetranslate.chat.ChatTranslationService;
import com.offlinetranslate.chat.OutgoingTranslateKeyListener;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.translate.TranslationEngine;
import com.offlinetranslate.ui.OfflineTranslatePanel;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Translates OSRS chat using language models downloaded once and run locally - no chat text is
 * sent to a third-party translation API at translate time. See the README for what's solid vs.
 * experimental in this build.
 */
@PluginDescriptor(
	name = "Offline Translate",
	description = "Translates chat using locally-downloaded language packs - no chat text sent to a translation API",
	tags = {"translate", "translation", "language", "chat"},
	enabledByDefault = false
)
public class OfflineTranslatePlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OfflineTranslateConfig config;

	@Inject
	private OfflineTranslatePanel panel;

	@Inject
	private ChatTranslationService chatTranslationService;

	@Inject
	private OutgoingTranslateKeyListener outgoingTranslateKeyListener;

	@Inject
	private ModelManager modelManager;

	@Inject
	private TranslationEngine translationEngine;

	private NavigationButton navigationButton;

	@Provides
	OfflineTranslateConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OfflineTranslateConfig.class);
	}

	@Override
	protected void startUp()
	{
		BufferedImage icon = PluginIconFactory.create();
		navigationButton = NavigationButton.builder()
			.tooltip("Offline Translate")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		eventBus.register(chatTranslationService);

		// Outgoing /t translation (OutgoingTranslateKeyListener) is disabled for now: two
		// rounds of fixes (avoiding the '/' PM collision, case-insensitive prefix matching,
		// never blocking on a cold load) all failed to stop it from corrupting the chatbox's
		// own send state on a real client - confirmed live, not just theorized. Mutating
		// VarClientStr.CHATBOX_TYPED_TEXT from a KeyListener on Enter does not behave the way
		// this was built assuming it would, and it's actively harmful (breaks sending any
		// message, not just translated ones) rather than just non-functional, so it's not
		// safe to leave enabled while unresolved. Everything else - side panel, incoming
		// detection/translation, flag icons - doesn't touch chat input and is unaffected.
		// keyManager.registerKeyListener(outgoingTranslateKeyListener);

		// Warm up whatever's currently configured so incoming translation (still enabled) is
		// fast on first use - warmUp() itself is a no-op if the pack isn't downloaded yet or
		// is already loaded.
		translationEngine.warmUp(config.preferredLanguage(), PackDirection.TO_ENGLISH);
		translationEngine.warmUp(config.outputLanguage(), PackDirection.FROM_ENGLISH);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navigationButton);
		eventBus.unregister(chatTranslationService);
		keyManager.unregisterKeyListener(outgoingTranslateKeyListener);
		chatTranslationService.shutdown();
		translationEngine.shutdown();
		modelManager.shutdown();
	}
}
