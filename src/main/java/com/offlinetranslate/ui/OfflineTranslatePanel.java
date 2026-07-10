package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.OfflineTranslateConfig;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class OfflineTranslatePanel extends PluginPanel
{
	private final ConfigManager configManager;
	private final OfflineTranslateConfig config;
	private final ModelManager modelManager;

	private final JComboBox<Language> preferredLanguageBox;
	private final JComboBox<Language> outputLanguageBox;
	private final JCheckBox autoDetectBox;
	private final JCheckBox autoUpdateOutputBox;
	private final TranslatedMessageLogPanel logPanel = new TranslatedMessageLogPanel();
	private final JPanel packListPanel = new JPanel();

	@Inject
	public OfflineTranslatePanel(ConfigManager configManager, OfflineTranslateConfig config, ModelManager modelManager)
	{
		this.configManager = configManager;
		this.config = config;
		this.modelManager = modelManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		top.add(sectionLabel("Your language"));
		preferredLanguageBox = new JComboBox<>(Language.values());
		preferredLanguageBox.setSelectedItem(config.preferredLanguage());
		preferredLanguageBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "preferredLanguage", preferredLanguageBox.getSelectedItem()));
		top.add(preferredLanguageBox);

		autoDetectBox = new JCheckBox("Auto-detect chat language", config.autoDetect());
		autoDetectBox.setOpaque(false);
		autoDetectBox.setForeground(ColorScheme.TEXT_COLOR);
		autoDetectBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "autoDetect", autoDetectBox.isSelected()));
		top.add(autoDetectBox);

		top.add(javax.swing.Box.createVerticalStrut(8));
		top.add(sectionLabel("Output language (for /t)"));
		outputLanguageBox = new JComboBox<>(java.util.Arrays.stream(Language.values())
			.filter(Language::supportsTranslationFromEnglish)
			.toArray(Language[]::new));
		outputLanguageBox.setSelectedItem(config.outputLanguage());
		outputLanguageBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "outputLanguage", outputLanguageBox.getSelectedItem()));
		top.add(outputLanguageBox);

		autoUpdateOutputBox = new JCheckBox("Auto-update to last speaker's language", config.autoUpdateOutputLanguage());
		autoUpdateOutputBox.setOpaque(false);
		autoUpdateOutputBox.setForeground(ColorScheme.TEXT_COLOR);
		autoUpdateOutputBox.addActionListener(e -> configManager.setConfiguration(
			OfflineTranslateConfig.GROUP, "autoUpdateOutputLanguage", autoUpdateOutputBox.isSelected()));
		top.add(autoUpdateOutputBox);

		top.add(javax.swing.Box.createVerticalStrut(10));
		top.add(sectionLabel("Language packs"));
		packListPanel.setLayout(new GridLayout(0, 1, 0, 2));
		packListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (Language language : Language.values())
		{
			if (language.isEnglish())
			{
				continue;
			}
			packListPanel.add(new LanguagePackRowPanel(modelManager, language));
		}
		JScrollPane packScroll = new JScrollPane(packListPanel);
		packScroll.setPreferredSize(new Dimension(0, 220));
		packScroll.setBorder(BorderFactory.createEmptyBorder());
		top.add(packScroll);

		add(top, BorderLayout.NORTH);

		JPanel logSection = new JPanel(new BorderLayout());
		logSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		logSection.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
		logSection.add(sectionLabel("Translated messages"), BorderLayout.NORTH);
		logSection.add(logPanel, BorderLayout.CENTER);
		add(logSection, BorderLayout.CENTER);
	}

	private static JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return label;
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
					.whenComplete((result, error) -> refreshPackList());
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
