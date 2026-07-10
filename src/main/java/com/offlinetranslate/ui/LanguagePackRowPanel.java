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
		setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		setAlignmentX(Component.LEFT_ALIGNMENT);
		// No explicit setMaximumSize() here - this row's downstream BoxLayout.maximumLayoutSize()
		// sums its (as-yet-nonexistent) children's max sizes as of *this* line, which is exactly
		// (0, 0) for an empty container. Calling setMaximumSize(getMaximumSize()) at this point,
		// as an earlier version of this did, stores that (0, 0) as a permanent explicit override
		// that never gets recomputed even once real children are added - confirmed live and
		// reproduced in isolation: the row's preferred height correctly grows once children
		// exist, but its *maximum* height stays locked at the stale value from before they did,
		// and BoxLayout clamps actual allocated height to the smaller of the two. That's what was
		// silently squashing every pack row down to a sliver with no visible buttons. Leaving
		// maximumSize unset lets BoxLayout compute it fresh from real children on every layout
		// pass instead of caching a wrong answer from before they existed - and since this row
		// only ever lives inside a scrollable list (no competing sibling ever needs to steal its
		// leftover space), there was never a real need to cap it at all.

		JLabel nameLabel = new JLabel(language.getDisplayName());
		nameLabel.setIcon(new ImageIcon(FlagIconFactory.create(language, 14, 10)));
		nameLabel.setIconTextGap(6);
		nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
		nameLabel.setForeground(PanelColors.TEXT);
		nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(nameLabel);

		add(javax.swing.Box.createVerticalStrut(4));

		// No fixed pixel width here (an earlier version used PluginPanel.PANEL_WIDTH - 30, a
		// guess that didn't account for this row's *own* nested JScrollPane also taking a
		// vertical scrollbar's worth of width away from what's actually available) - Integer.
		// MAX_VALUE lets these stretch to whatever width the row itself ends up with, which is
		// however much is actually left after every ancestor's own scrollbar/border/padding, not
		// a number computed once and assumed to still be right several containers later.
		// Confirmed live: the fixed-width version was wider than the real available space,
		// forcing an unwanted horizontal scrollbar.
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		downloadButton.setToolTipText("Both directions: their " + language.getDisplayName() + " chat -> you, and your messages -> " + language.getDisplayName());
		downloadButton.addActionListener(e -> downloadMissing());
		buttons.add(downloadButton);
		deleteButton.setToolTipText("Remove the downloaded " + language.getDisplayName() + " pack");
		deleteButton.addActionListener(e -> confirmDelete());
		buttons.add(deleteButton);
		add(buttons);

		progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		progressBar.setPreferredSize(new Dimension(0, 6));
		progressBar.setVisible(false);
		add(javax.swing.Box.createVerticalStrut(4));
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
