package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.OfflineTranslateConfig;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.translate.TranslationEngine;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Without {@code @Singleton} here, Guice hands out a separate instance to every injection point -
 * one for {@code OfflineTranslatePlugin.panel} (the instance actually wrapped into the
 * NavigationButton and shown in the sidebar) and a *different* one for
 * {@code ChatTranslationService.panel} (the instance {@code logTranslatedMessage()} etc. are
 * actually called on). Confirmed live: translation was succeeding and logging on every message
 * (both auto-detect and the right-click path), yet nothing ever appeared - because it was being
 * logged to an invisible clone that was never attached to anything. No layout/scrolling change
 * could ever have fixed that; the panel that received the updates simply wasn't the one on
 * screen.
 */
@Singleton
public class OfflineTranslatePanel extends PluginPanel
{
	private final ConfigManager configManager;
	private final OfflineTranslateConfig config;
	private final ModelManager modelManager;
	private final TranslationEngine translationEngine;
	private final Client client;
	private final ClientThread clientThread;

	private final JComboBox<Language> preferredLanguageBox;
	private final JComboBox<Language> outputLanguageBox;
	private final JCheckBox autoDetectBox;
	private final JCheckBox autoUpdateOutputBox;
	private final JCheckBox showFlagsBox;
	private final TranslatedMessageLogPanel logPanel = new TranslatedMessageLogPanel();
	private final JPanel packListPanel = new JPanel();

	@Inject
	public OfflineTranslatePanel(ConfigManager configManager, OfflineTranslateConfig config, ModelManager modelManager,
		TranslationEngine translationEngine, Client client, ClientThread clientThread)
	{
		this.configManager = configManager;
		this.config = config;
		this.modelManager = modelManager;
		this.translationEngine = translationEngine;
		this.client = client;
		this.clientThread = clientThread;

		// RuneLite's own PluginPanel (our superclass) already wraps every plugin panel in its
		// own outer JScrollPane before adding it to the sidebar (see PluginPanel.java: this gets
		// added to a BorderLayout.NORTH slot inside a JScrollPane, and ClientUI.addNavigation
		// adds getWrappedPanel(), not this panel directly) - so a manual outer scrollpane here
		// would just be a redundant, confusing second layer. A single BoxLayout column is all
		// this needs; RuneLite's own scrolling reaches every section in it, same as it already
		// does for every other plugin's side panel.
		JPanel top = this;
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(PanelColors.BACKGROUND);
		top.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		top.add(sectionHeader("Your language"));
		preferredLanguageBox = new JComboBox<>(Language.values());
		preferredLanguageBox.setSelectedItem(config.preferredLanguage());
		preferredLanguageBox.addActionListener(e -> {
			Language selected = (Language) preferredLanguageBox.getSelectedItem();
			configManager.setConfiguration(OfflineTranslateConfig.GROUP, "preferredLanguage", selected);
			translationEngine.warmUp(selected, PackDirection.TO_ENGLISH);
		});
		styleComboBox(preferredLanguageBox);
		top.add(preferredLanguageBox);

		top.add(javax.swing.Box.createVerticalStrut(8));
		autoDetectBox = new JCheckBox("Auto-detect chat language", config.autoDetect());
		styleCheckbox(autoDetectBox);
		autoDetectBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "autoDetect", autoDetectBox.isSelected()));
		top.add(autoDetectBox);

		top.add(javax.swing.Box.createVerticalStrut(4));
		showFlagsBox = new JCheckBox("Show flags in chat", config.showFlagsInChat());
		styleCheckbox(showFlagsBox);
		showFlagsBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "showFlagsInChat", showFlagsBox.isSelected()));
		top.add(showFlagsBox);

		top.add(javax.swing.Box.createVerticalStrut(18));
		top.add(sectionHeader("Output language (for the translate hotkey)"));
		outputLanguageBox = new JComboBox<>(java.util.Arrays.stream(Language.values())
			.filter(Language::supportsTranslationFromEnglish)
			.toArray(Language[]::new));
		outputLanguageBox.setSelectedItem(config.outputLanguage());
		outputLanguageBox.addActionListener(e -> {
			Language selected = (Language) outputLanguageBox.getSelectedItem();
			configManager.setConfiguration(OfflineTranslateConfig.GROUP, "outputLanguage", selected);
			translationEngine.warmUp(selected, PackDirection.FROM_ENGLISH);
		});
		styleComboBox(outputLanguageBox);
		top.add(outputLanguageBox);

		top.add(javax.swing.Box.createVerticalStrut(8));
		autoUpdateOutputBox = new JCheckBox("Auto-update to last speaker's language", config.autoUpdateOutputLanguage());
		styleCheckbox(autoUpdateOutputBox);
		autoUpdateOutputBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "autoUpdateOutputLanguage", autoUpdateOutputBox.isSelected()));
		top.add(autoUpdateOutputBox);

		top.add(javax.swing.Box.createVerticalStrut(18));
		top.add(sectionHeaderWithAction("Installed language packs", "Download all", this::downloadAllMissing));

		packListPanel.setLayout(new BoxLayout(packListPanel, BoxLayout.Y_AXIS));
		packListPanel.setOpaque(false);
		for (Language language : Language.values())
		{
			if (language.isEnglish())
			{
				continue;
			}
			LanguagePackRowPanel row = new LanguagePackRowPanel(modelManager, translationEngine, language, this::notifyGameChat);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			packListPanel.add(row);
			packListPanel.add(javax.swing.Box.createVerticalStrut(6));
		}
		JScrollPane packScroll = new JScrollPane(packListPanel);
		packScroll.setPreferredSize(new Dimension(0, 260));
		packScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
		packScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		packScroll.setBorder(BorderFactory.createEmptyBorder());
		packScroll.setOpaque(false);
		packScroll.getViewport().setOpaque(false);
		top.add(packScroll);

		top.add(javax.swing.Box.createVerticalStrut(18));
		top.add(sectionHeader("Translated messages"));
		// logPanel has its own internal JScrollPane (see TranslatedMessageLogPanel) with no
		// fixed size of its own, so inside this outer BoxLayout column it needs an explicit
		// height or it collapses to near-zero - same reasoning as packScroll's fixed height
		// above. Raised from 300 to 500 per feedback that a handful of entries already needed
		// scrolling within the section - most people will spend more time reading this list than
		// the pack list above it, so it gets more of the panel's height budget.
		logPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		logPanel.setPreferredSize(new Dimension(0, 500));
		logPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 500));
		top.add(logPanel);
	}

	/** Same chat-message mechanism {@code ChatTranslationService.warn()} uses - lets pack-download/delete completions surface in-game without needing to watch the side panel. Safe to call off the EDT. */
	private void notifyGameChat(String message)
	{
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", "[Offline Translate] " + message, null));
	}

	private void downloadAllMissing()
	{
		List<CompletableFuture<Void>> started = new ArrayList<>();
		for (Component c : packListPanel.getComponents())
		{
			if (c instanceof LanguagePackRowPanel)
			{
				CompletableFuture<Void> future = ((LanguagePackRowPanel) c).downloadMissing();
				if (future != null)
				{
					started.add(future);
				}
			}
		}
		if (started.isEmpty())
		{
			return;
		}
		CompletableFuture.allOf(started.toArray(new CompletableFuture[0]))
			.whenComplete((result, error) -> notifyGameChat("Finished downloading all language packs."));
	}

	private static void styleComboBox(JComboBox<Language> comboBox)
	{
		comboBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		comboBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, comboBox.getPreferredSize().height));
		comboBox.setBackground(PanelColors.CARD);
		comboBox.setForeground(PanelColors.TEXT);
		comboBox.setFont(FontManager.getRunescapeFont());
	}

	private static void styleCheckbox(JCheckBox checkbox)
	{
		checkbox.setOpaque(false);
		checkbox.setForeground(PanelColors.TEXT_MUTED);
		checkbox.setFont(FontManager.getRunescapeSmallFont());
		checkbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		checkbox.setFocusPainted(false);
	}

	/** A compact, muted-uppercase section title, matching a "settings group label" look rather than a heavy divider. */
	private static JLabel sectionHeader(String text)
	{
		JLabel label = new JLabel(text.toUpperCase());
		label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		label.setForeground(PanelColors.TEXT_MUTED);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		return label;
	}

	/** A section header with a small text action pinned to the right (e.g. "Download all"), for sections that need one. */
	private static JPanel sectionHeaderWithAction(String text, String actionText, Runnable action)
	{
		JPanel row = new JPanel(new java.awt.BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getMaximumSize().height));
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JLabel label = new JLabel(text.toUpperCase());
		label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		label.setForeground(PanelColors.TEXT_MUTED);
		row.add(label, java.awt.BorderLayout.WEST);

		javax.swing.JButton actionButton = new javax.swing.JButton(actionText);
		actionButton.setFont(FontManager.getRunescapeSmallFont());
		actionButton.setForeground(PanelColors.ACCENT);
		actionButton.setFocusPainted(false);
		actionButton.setBorderPainted(false);
		actionButton.setContentAreaFilled(false);
		actionButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
		actionButton.addActionListener(e -> action.run());
		row.add(actionButton, java.awt.BorderLayout.EAST);

		return row;
	}

	/** Appends a translated incoming message to the side panel log. Safe to call off the EDT. */
	public void logTranslatedMessage(TranslatedMessageEntry entry)
	{
		SwingUtilities.invokeLater(() -> logPanel.addEntry(entry));
	}

	/**
	 * Updates the output-language combo box to reflect a newly detected language, if
	 * "auto-update output language" is enabled. Safe to call off the EDT.
	 */
	public void setDetectedOutputLanguage(Language language)
	{
		if (!config.autoUpdateOutputLanguage() || !language.supportsTranslationFromEnglish())
		{
			return;
		}
		SwingUtilities.invokeLater(() -> outputLanguageBox.setSelectedItem(language));
	}

	/** Prompts the user to download a missing language pack. Safe to call off the EDT. */
	public void promptDownloadMissingPack(Language language, PackDirection direction)
	{
		SwingUtilities.invokeLater(() -> {
			String directionLabel = direction == PackDirection.TO_ENGLISH
				? language.getDisplayName() + " -> English"
				: "English -> " + language.getDisplayName();
			int choice = JOptionPane.showConfirmDialog(this,
				"You don't have a " + directionLabel + " language pack downloaded.\nDownload it now?",
				"Offline Translate",
				JOptionPane.YES_NO_OPTION);
			if (choice == JOptionPane.YES_OPTION)
			{
				modelManager.download(language, direction, null)
					.whenComplete((result, error) -> {
						if (error == null)
						{
							translationEngine.warmUp(language, direction);
							notifyGameChat(language.getDisplayName() + " pack downloaded.");
						}
						refreshPackList();
					});
			}
		});
	}

	private void refreshPackList()
	{
		SwingUtilities.invokeLater(() -> {
			for (Component c : packListPanel.getComponents())
			{
				if (c instanceof LanguagePackRowPanel)
				{
					((LanguagePackRowPanel) c).refresh();
				}
			}
		});
	}
}
