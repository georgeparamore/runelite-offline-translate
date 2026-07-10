package com.offlinetranslate.ui;

import com.offlinetranslate.chat.FlagIconFactory;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import net.runelite.client.ui.FontManager;

/** Scrollable log of incoming chat messages that have been translated for you. */
class TranslatedMessageLogPanel extends JPanel
{
	private static final int MAX_ENTRIES = 50;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	private final JPanel entriesPanel = new JPanel();
	private final JLabel emptyLabel = new JLabel("No translated messages yet", SwingConstants.CENTER);

	TranslatedMessageLogPanel()
	{
		setLayout(new BorderLayout());
		setOpaque(false);

		// Drawn TrashIcon, not a "🗑" glyph: the RuneScape bitmap font this button uses can't be
		// trusted to have Unicode Dingbats coverage any more than it had flag-emoji coverage
		// (see FlagIconFactory) - confirmed live as garbled text elsewhere in this panel where an
		// emoji was left in place of a drawn icon.
		JButton clearButton = new JButton("Clear", new TrashIcon());
		clearButton.setFont(FontManager.getRunescapeSmallFont());
		clearButton.setForeground(PanelColors.TEXT_MUTED);
		clearButton.setIconTextGap(4);
		clearButton.setFocusPainted(false);
		clearButton.setBorderPainted(false);
		clearButton.setContentAreaFilled(false);
		clearButton.setMargin(new java.awt.Insets(1, 6, 1, 6));
		clearButton.addActionListener(e -> clear());

		JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		headerRow.setOpaque(false);
		headerRow.add(clearButton);
		add(headerRow, BorderLayout.NORTH);

		entriesPanel.setLayout(new BoxLayout(entriesPanel, BoxLayout.Y_AXIS));
		entriesPanel.setOpaque(false);

		emptyLabel.setForeground(PanelColors.TEXT_MUTED);
		emptyLabel.setFont(FontManager.getRunescapeSmallFont());
		emptyLabel.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
		entriesPanel.add(emptyLabel);

		JScrollPane scrollPane = new JScrollPane(entriesPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.getVerticalScrollBar().setUnitIncrement(12);
		add(scrollPane, BorderLayout.CENTER);
	}

	void addEntry(TranslatedMessageEntry entry)
	{
		entriesPanel.remove(emptyLabel);

		RoundedPanel row = new RoundedPanel(10, PanelColors.CARD);
		row.setLayout(new BorderLayout(8, 2));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 6, 0),
			BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getMaximumSize().height));

		JLabel headerLabel = new JLabel(entry.getSender());
		headerLabel.setForeground(PanelColors.ACCENT);
		headerLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		if (entry.getDetectedLanguage() != null)
		{
			headerLabel.setIcon(new ImageIcon(FlagIconFactory.create(entry.getDetectedLanguage(), 16, 11)));
			headerLabel.setIconTextGap(6);
			headerLabel.setHorizontalTextPosition(SwingConstants.RIGHT);
		}

		JLabel timeLabel = new JLabel(TIME_FORMAT.format(Instant.ofEpochMilli(entry.getTimestampMillis())));
		timeLabel.setForeground(PanelColors.TEXT_MUTED);
		timeLabel.setFont(FontManager.getRunescapeSmallFont());

		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.add(headerLabel, BorderLayout.WEST);
		headerRow.add(timeLabel, BorderLayout.EAST);

		JLabel translatedLabel = new JLabel("<html><body style='width: 150px'>" + escape(entry.getTranslatedText()) + "</body></html>");
		translatedLabel.setForeground(PanelColors.TEXT);
		translatedLabel.setFont(FontManager.getRunescapeSmallFont());

		JLabel originalLabel = new JLabel("<html><body style='width: 150px'>" + escape(entry.getOriginalText()) + "</body></html>");
		originalLabel.setForeground(PanelColors.TEXT_MUTED);
		originalLabel.setFont(originalLabel.getFont().deriveFont(originalLabel.getFont().getSize2D() - 1f));
		originalLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));

		JPanel textPanel = new JPanel();
		textPanel.setOpaque(false);
		textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
		textPanel.add(translatedLabel);
		textPanel.add(originalLabel);

		row.add(headerRow, BorderLayout.NORTH);
		row.add(textPanel, BorderLayout.CENTER);

		entriesPanel.add(row, 0);
		entriesPanel.add(javax.swing.Box.createVerticalStrut(6), 1);
		while (entriesPanel.getComponentCount() > MAX_ENTRIES * 2)
		{
			entriesPanel.remove(entriesPanel.getComponentCount() - 1);
		}
		entriesPanel.revalidate();
		entriesPanel.repaint();
	}

	private void clear()
	{
		entriesPanel.removeAll();
		entriesPanel.add(emptyLabel);
		entriesPanel.revalidate();
		entriesPanel.repaint();
	}

	private static String escape(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
