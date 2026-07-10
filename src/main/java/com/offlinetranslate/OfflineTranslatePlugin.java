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
import net.runelite.client.menus.MenuManager;
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
	private MenuManager menuManager;

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

		// Adds "Translate" to every player's right-click menu (same mechanism "Add friend"/
		// "Report" use) - works both on world player right-clicks and chat name right-clicks,
		// since OSRS handles both through the same underlying player-option system. Click
		// handling lives in ChatTranslationService.onMenuOptionClicked.
		menuManager.addPlayerMenuItem(ChatTranslationService.RIGHT_CLICK_OPTION);

		// Re-enabled with a redesigned mechanism: translate-in-place on a dedicated hotkey
		// (default Ctrl+T), completely decoupled from Enter/sending. The earlier version tried
		// to rewrite the chatbox text during the Enter keypress itself and was confirmed live
		// to corrupt the client's send state - see OutgoingTranslateKeyListener's class javadoc
		// for the full reasoning on why this approach avoids that failure mode.
		keyManager.registerKeyListener(outgoingTranslateKeyListener);

		// Warm up whatever's currently configured so translation is fast on first use -
		// warmUp() itself is a no-op if the pack isn't downloaded yet or is already loaded.
		translationEngine.warmUp(config.preferredLanguage(), PackDirection.TO_ENGLISH);
		translationEngine.warmUp(config.outputLanguage(), PackDirection.FROM_ENGLISH);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navigationButton);
		eventBus.unregister(chatTranslationService);
		menuManager.removePlayerMenuItem(ChatTranslationService.RIGHT_CLICK_OPTION);
		keyManager.unregisterKeyListener(outgoingTranslateKeyListener);
		chatTranslationService.shutdown();
		translationEngine.shutdown();
		modelManager.shutdown();
	}
}
