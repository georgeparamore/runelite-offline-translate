package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.chat.FlagIconFactory;
import com.offlinetranslate.model.DownloadProgress;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import com.offlinetranslate.translate.TranslationEngine;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * One row in the language pack manager, as a rounded card: a language, a single "Download"
 * button that fetches both directions (lang-&gt;English and English-&gt;lang) together - split
 * incoming/outgoing buttons were confirmed confusing, since downloading just one direction
 * leaves translation only working one way, which isn't a state anyone actually wants - and a
 * "Delete" button (once anything's downloaded) that removes both directions after a confirmation
 * dialog.
 */
class LanguagePackRowPanel extends RoundedPanel
{
	private static final int ROW_WIDTH = PluginPanel.PANEL_WIDTH - 30;

	private final ModelManager modelManager;
	private final TranslationEngine translationEngine;
	private final Language language;
	/** Reports pack-download completion in-game (a chat message), independent of Swing - see {@link OfflineTranslatePanel}'s constructor for the actual client/clientThread call. */
	private final Consumer<String> notifier;

	private final PillButton downloadButton = new PillButton("Download", PanelColors.ACCENT, PanelColors.TEXT);
	private final PillButton deleteButton = new PillButton("Delete", PanelColors.CARD_ALT, PanelColors.DANGER);
	private final JProgressBar progressBar = new JProgressBar(0, 100);

	LanguagePackRowPanel(ModelManager modelManager, TranslationEngine translationEngine, Language language, Consumer<String> notifier)
	{
		super(10, PanelColors.CARD);
		this.modelManager = modelManager;
		this.translationEngine = translationEngine;
		this.language = language;
		this.notifier = notifier;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, getMaximumSize().height));
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel nameLabel = new JLabel(language.getDisplayName());
		nameLabel.setIcon(new ImageIcon(FlagIconFactory.create(language, 16, 11)));
		nameLabel.setIconTextGap(6);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(PanelColors.TEXT);
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(nameLabel);

		add(javax.swing.Box.createVerticalStrut(6));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(ROW_WIDTH, 30));
		downloadButton.setToolTipText("Both directions: their " + language.getDisplayName() + " chat -> you, and your messages -> " + language.getDisplayName());
		downloadButton.addActionListener(e -> downloadMissing());
		buttons.add(downloadButton);
		deleteButton.setToolTipText("Remove the downloaded " + language.getDisplayName() + " pack");
		deleteButton.addActionListener(e -> confirmDelete());
		buttons.add(deleteButton);
		add(buttons);

		progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		progressBar.setMaximumSize(new Dimension(ROW_WIDTH, 8));
		progressBar.setPreferredSize(new Dimension(ROW_WIDTH, 8));
		progressBar.setVisible(false);
		add(javax.swing.Box.createVerticalStrut(6));
		add(progressBar);

		refresh();
	}

	/**
	 * Starts downloading whichever direction(s) aren't already ready or in progress, reporting
	 * one combined completion for the whole row via {@link #notifier} rather than one message
	 * per direction, since the two directions are no longer a user-facing distinction. Returns
	 * null if there was nothing to do (used by the panel-wide "Download all" button to know
	 * whether this row contributed any work to wait on).
	 */
	CompletableFuture<Void> downloadMissing()
	{
		List<PackDirection> missing = new ArrayList<>();
		if (isMissing(modelManager.getStatus(language, PackDirection.TO_ENGLISH)))
		{
			missing.add(PackDirection.TO_ENGLISH);
		}
		if (language.supportsTranslationFromEnglish() && isMissing(modelManager.getStatus(language, PackDirection.FROM_ENGLISH)))
		{
			missing.add(PackDirection.FROM_ENGLISH);
		}
		if (missing.isEmpty())
		{
			return null;
		}

		downloadButton.setEnabled(false);
		deleteButton.setEnabled(false);
		progressBar.setVisible(true);
		progressBar.setValue(0);

		List<CompletableFuture<Void>> parts = new ArrayList<>();
		for (PackDirection direction : missing)
		{
			parts.add(modelManager.download(language, direction, (DownloadProgress progress) ->
				SwingUtilities.invokeLater(() -> progressBar.setValue((int) (progress.overallFraction() * 100))))
				.whenComplete((result, error) -> {
					if (error == null)
					{
						translationEngine.warmUp(language, direction);
					}
				})
				// Swallow the per-direction error here rather than letting it propagate into the
				// allOf() below: a failure in one direction (e.g. incoming) shouldn't stop the
				// row's combined future from ever completing, since the other direction may
				// still succeed and refresh() (which reads status directly, not from this
				// future's result) is what actually reflects success/failure per button.
				.exceptionally(error -> null));
		}

		return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
			.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
				progressBar.setVisible(false);
				refresh();
				if (modelManager.getStatus(language, PackDirection.TO_ENGLISH) == PackStatus.READY
					&& (!language.supportsTranslationFromEnglish() || modelManager.getStatus(language, PackDirection.FROM_ENGLISH) == PackStatus.READY))
				{
					notifier.accept(language.getDisplayName() + " pack downloaded.");
				}
			}));
	}

	private void confirmDelete()
	{
		int choice = JOptionPane.showConfirmDialog(this,
			"Delete the downloaded " + language.getDisplayName() + " language pack?\nYou'll need to download it again to translate " + language.getDisplayName() + " chat.",
			"Offline Translate",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}

		modelManager.deletePack(language, PackDirection.TO_ENGLISH);
		if (language.supportsTranslationFromEnglish())
		{
			modelManager.deletePack(language, PackDirection.FROM_ENGLISH);
		}
		notifier.accept(language.getDisplayName() + " pack deleted.");
		refresh();
	}

	private static boolean isMissing(PackStatus status)
	{
		return status == PackStatus.NOT_DOWNLOADED || status == PackStatus.ERROR;
	}

	void refresh()
	{
		PackStatus toEnglish = modelManager.getStatus(language, PackDirection.TO_ENGLISH);
		PackStatus fromEnglish = language.supportsTranslationFromEnglish()
			? modelManager.getStatus(language, PackDirection.FROM_ENGLISH)
			: PackStatus.READY;

		boolean anyDownloaded = toEnglish == PackStatus.READY || toEnglish == PackStatus.DOWNLOADING
			|| fromEnglish == PackStatus.READY || fromEnglish == PackStatus.DOWNLOADING;
		deleteButton.setVisible(anyDownloaded);

		if (toEnglish == PackStatus.DOWNLOADING || fromEnglish == PackStatus.DOWNLOADING)
		{
			downloadButton.setText("Downloading...");
			downloadButton.setEnabled(false);
			deleteButton.setEnabled(false);
		}
		else if (toEnglish == PackStatus.READY && fromEnglish == PackStatus.READY)
		{
			downloadButton.setText("Downloaded ✓");
			downloadButton.setEnabled(false);
			deleteButton.setEnabled(true);
		}
		else if (toEnglish == PackStatus.ERROR || fromEnglish == PackStatus.ERROR)
		{
			downloadButton.setText("Retry");
			downloadButton.setEnabled(true);
			deleteButton.setEnabled(true);
		}
		else
		{
			downloadButton.setText("Download");
			downloadButton.setEnabled(true);
			deleteButton.setEnabled(true);
		}
	}
}
