package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.model.DownloadProgress;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import com.offlinetranslate.translate.TranslationEngine;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;

/**
 * One row in the language pack manager: a language, its incoming (lang-&gt;English) and
 * outgoing (English-&gt;lang) pack status, and download buttons for each.
 */
class LanguagePackRowPanel extends JPanel
{
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

		setLayout(new BorderLayout(5, 2));
		setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel nameLabel = new JLabel(language.getFlagEmoji() + " " + language.getDisplayName());
		add(nameLabel, BorderLayout.WEST);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		buttons.setOpaque(false);
		incomingButton.setToolTipText("Download " + language.getDisplayName() + " -> English pack (translate their chat to you)");
		outgoingButton.setToolTipText("Download English -> " + language.getDisplayName() + " pack (translate your /t messages to them)");
		incomingButton.addActionListener(e -> download(PackDirection.TO_ENGLISH, incomingButton));
		outgoingButton.addActionListener(e -> download(PackDirection.FROM_ENGLISH, outgoingButton));
		buttons.add(incomingButton);
		if (language.supportsTranslationFromEnglish())
		{
			buttons.add(outgoingButton);
		}
		add(buttons, BorderLayout.EAST);

		progressBar.setPreferredSize(new Dimension(100, 12));
		progressBar.setVisible(false);
		add(progressBar, BorderLayout.SOUTH);

		refresh();
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
