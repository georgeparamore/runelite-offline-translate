package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.OfflineTranslateConfig;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.translate.TranslationEngine;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
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

	private final JComboBox<Language> preferredLanguageBox;
	private final JComboBox<Language> outputLanguageBox;
	private final JCheckBox autoDetectBox;
	private final JCheckBox autoUpdateOutputBox;
	private final TranslatedMessageLogPanel logPanel = new TranslatedMessageLogPanel();
	private final JPanel packListPanel = new JPanel();

	@Inject
	public OfflineTranslatePanel(ConfigManager configManager, OfflineTranslateConfig config, ModelManager modelManager, TranslationEngine translationEngine)
	{
		this.configManager = configManager;
		this.config = config;
		this.modelManager = modelManager;
		this.translationEngine = translationEngine;

		// RuneLite's own PluginPanel (our superclass) already wraps every plugin panel in its
		// own outer JScrollPane before adding it to the sidebar (see PluginPanel.java: this gets
		// added to a BorderLayout.NORTH slot inside a JScrollPane, and ClientUI.addNavigation
		// adds getWrappedPanel(), not this panel directly) - so a manual outer scrollpane here
		// would just be a redundant, confusing second layer. A single BoxLayout column is all
		// this needs; RuneLite's own scrolling reaches every section in it, same as it already
		// does for every other plugin's side panel.
		JPanel top = this;
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		top.add(sectionHeader("Your language"));
		preferredLanguageBox = new JComboBox<>(Language.values());
		preferredLanguageBox.setSelectedItem(config.preferredLanguage());
		preferredLanguageBox.addActionListener(e -> {
			Language selected = (Language) preferredLanguageBox.getSelectedItem();
			configManager.setConfiguration(OfflineTranslateConfig.GROUP, "preferredLanguage", selected);
			translationEngine.warmUp(selected, PackDirection.TO_ENGLISH);
		});
		stretchWidth(preferredLanguageBox);
		top.add(preferredLanguageBox);

		top.add(javax.swing.Box.createVerticalStrut(6));
		autoDetectBox = new JCheckBox("Auto-detect chat language", config.autoDetect());
		styleCheckbox(autoDetectBox);
		autoDetectBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "autoDetect", autoDetectBox.isSelected()));
		top.add(autoDetectBox);

		top.add(javax.swing.Box.createVerticalStrut(14));
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
		stretchWidth(outputLanguageBox);
		top.add(outputLanguageBox);

		top.add(javax.swing.Box.createVerticalStrut(6));
		autoUpdateOutputBox = new JCheckBox("Auto-update to last speaker's language", config.autoUpdateOutputLanguage());
		styleCheckbox(autoUpdateOutputBox);
		autoUpdateOutputBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "autoUpdateOutputLanguage", autoUpdateOutputBox.isSelected()));
		top.add(autoUpdateOutputBox);

		top.add(javax.swing.Box.createVerticalStrut(14));
		top.add(sectionHeader("Language packs"));

		JButton downloadAllButton = new JButton("Download all");
		downloadAllButton.setFont(FontManager.getRunescapeSmallFont());
		downloadAllButton.setFocusPainted(false);
		downloadAllButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		downloadAllButton.addActionListener(e -> {
			for (java.awt.Component c : packListPanel.getComponents())
			{
				if (c instanceof LanguagePackRowPanel)
				{
					((LanguagePackRowPanel) c).downloadMissing();
				}
			}
		});
		top.add(downloadAllButton);
		top.add(javax.swing.Box.createVerticalStrut(6));

		packListPanel.setLayout(new GridLayout(0, 1));
		packListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (Language language : Language.values())
		{
			if (language.isEnglish())
			{
				continue;
			}
			packListPanel.add(new LanguagePackRowPanel(modelManager, translationEngine, language));
		}
		JScrollPane packScroll = new JScrollPane(packListPanel);
		packScroll.setPreferredSize(new Dimension(0, 220));
		packScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
		packScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		packScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR.darker()));
		top.add(packScroll);

		top.add(javax.swing.Box.createVerticalStrut(14));
		top.add(sectionHeader("Translated messages"));
		// logPanel has its own internal JScrollPane (see TranslatedMessageLogPanel) with no
		// fixed size of its own, so inside this outer BoxLayout column it needs an explicit
		// height or it collapses to near-zero - same reasoning as packScroll's fixed height
		// above. Kept tall enough to be genuinely useful once you scroll the outer pane down to
		// it, rather than a token sliver.
		logPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		logPanel.setPreferredSize(new Dimension(0, 300));
		logPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
		top.add(logPanel);
	}

	private static void stretchWidth(javax.swing.JComponent component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
	}

	private static void styleCheckbox(JCheckBox checkbox)
	{
		checkbox.setOpaque(false);
		checkbox.setForeground(ColorScheme.TEXT_COLOR);
		checkbox.setFont(FontManager.getRunescapeSmallFont());
		checkbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		checkbox.setFocusPainted(false);
	}

	/**
	 * A section title with a short accent-colored underline directly beneath it, instead of a
	 * full-width gray divider bar - reads as a modern, compact heading rather than a slab of
	 * empty space between sections.
	 */
	private static JPanel sectionHeader(String text)
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getMaximumSize().height));

		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(label);

		header.add(javax.swing.Box.createVerticalStrut(3));

		JPanel underline = new JPanel();
		underline.setBackground(ColorScheme.BRAND_ORANGE);
		underline.setAlignmentX(Component.LEFT_ALIGNMENT);
		underline.setMaximumSize(new Dimension(28, 2));
		underline.setPreferredSize(new Dimension(28, 2));
		header.add(underline);

		header.add(javax.swing.Box.createVerticalStrut(6));
		return header;
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
						}
						refreshPackList();
					});
			}
		});
	}

	private void refreshPackList()
	{
		SwingUtilities.invokeLater(() -> {
			for (java.awt.Component c : packListPanel.getComponents())
			{
				if (c instanceof LanguagePackRowPanel)
				{
					((LanguagePackRowPanel) c).refresh();
				}
			}
		});
	}
}
