package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.chat.FlagIconFactory;
import com.offlinetranslate.model.DownloadProgress;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import com.offlinetranslate.translate.TranslationEngine;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * One row in the language pack manager: a language, its incoming (lang-&gt;English) and
 * outgoing (English-&gt;lang) pack status, and download buttons for each. Stacked vertically
 * (name on its own line, buttons below) rather than side-by-side, since the sidebar is only
 * {@link PluginPanel#PANEL_WIDTH} wide - a horizontal layout was squeezing longer language
 * names (e.g. "Indonesian", "Portuguese") down to nothing next to two buttons.
 */
class LanguagePackRowPanel extends JPanel
{
	private static final int ROW_WIDTH = PluginPanel.PANEL_WIDTH - 20;

	private final ModelManager modelManager;
	private final TranslationEngine translationEngine;
	private final Language language;

	private final JButton incomingButton = new JButton();
	private final JButton outgoingButton = new JButton();
	private final JProgressBar progressBar = new JProgressBar(0, 100);

	LanguagePackRowPanel(ModelManager modelManager, TranslationEngine translationEngine, Language language)
	{
		this.modelManager = modelManager;
		this.translationEngine = translationEngine;
		this.language = language;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setMaximumSize(new Dimension(Integer.MAX_VALUE, getMaximumSize().height));
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel nameLabel = new JLabel(language.getDisplayName());
		nameLabel.setIcon(new ImageIcon(FlagIconFactory.create(language, 16, 11)));
		nameLabel.setIconTextGap(6);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(ColorScheme.TEXT_COLOR);
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(nameLabel);

		add(javax.swing.Box.createVerticalStrut(4));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(ROW_WIDTH, 30));
		incomingButton.setToolTipText("Their " + language.getDisplayName() + " chat -> translated to you");
		outgoingButton.setToolTipText("Your outgoing messages -> translated to " + language.getDisplayName());
		incomingButton.addActionListener(e -> download(PackDirection.TO_ENGLISH, incomingButton));
		outgoingButton.addActionListener(e -> download(PackDirection.FROM_ENGLISH, outgoingButton));
		styleButton(incomingButton);
		styleButton(outgoingButton);
		buttons.add(incomingButton);
		if (language.supportsTranslationFromEnglish())
		{
			buttons.add(outgoingButton);
		}
		add(buttons);

		progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		progressBar.setMaximumSize(new Dimension(ROW_WIDTH, 10));
		progressBar.setPreferredSize(new Dimension(ROW_WIDTH, 10));
		progressBar.setVisible(false);
		add(progressBar);

		refresh();
	}

	private static void styleButton(JButton button)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setMargin(new java.awt.Insets(2, 8, 2, 8));
		button.setFocusPainted(false);
	}

	/** Starts downloading whichever of this row's packs (incoming/outgoing) aren't already ready or in progress. Used by the panel-wide "Download all" button. */
	void downloadMissing()
	{
		if (isMissing(modelManager.getStatus(language, PackDirection.TO_ENGLISH)))
		{
			download(PackDirection.TO_ENGLISH, incomingButton);
		}
		if (language.supportsTranslationFromEnglish() && isMissing(modelManager.getStatus(language, PackDirection.FROM_ENGLISH)))
		{
			download(PackDirection.FROM_ENGLISH, outgoingButton);
		}
	}

	private static boolean isMissing(PackStatus status)
	{
		return status == PackStatus.NOT_DOWNLOADED || status == PackStatus.ERROR;
	}

	void refresh()
	{
		updateButton(incomingButton, modelManager.getStatus(language, PackDirection.TO_ENGLISH), "Incoming");
		if (language.supportsTranslationFromEnglish())
		{
			updateButton(outgoingButton, modelManager.getStatus(language, PackDirection.FROM_ENGLISH), "Outgoing");
		}
	}

	private void updateButton(JButton button, PackStatus status, String label)
	{
		switch (status)
		{
			case READY:
				button.setText(label + " ✓");
				button.setEnabled(false);
				break;
			case DOWNLOADING:
				button.setText(label + "...");
				button.setEnabled(false);
				break;
			case ERROR:
				button.setText(label + " retry");
				button.setEnabled(true);
				break;
			case NOT_DOWNLOADED:
			default:
				button.setText(label + " ↓");
				button.setEnabled(true);
				break;
		}
	}

	private void download(PackDirection direction, JButton button)
	{
		button.setEnabled(false);
		progressBar.setVisible(true);
		progressBar.setValue(0);

		modelManager.download(language, direction, (DownloadProgress progress) ->
			SwingUtilities.invokeLater(() -> progressBar.setValue((int) (progress.overallFraction() * 100))))
			.whenComplete((result, error) -> {
				if (error == null)
				{
					translationEngine.warmUp(language, direction);
				}
				SwingUtilities.invokeLater(() -> {
					progressBar.setVisible(false);
					refresh();
				});
			});
	}
}
