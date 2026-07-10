package com.offlinetranslate.ui;

import com.offlinetranslate.chat.FlagIconFactory;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** Scrollable log of incoming chat messages that have been translated for you. */
class TranslatedMessageLogPanel extends JPanel
{
	private static final int MAX_ENTRIES = 50;
	private static final Color CARD_BACKGROUND = new Color(0x2b, 0x2b, 0x2b);
	private static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	private final JPanel entriesPanel = new JPanel();
	private final JLabel emptyLabel = new JLabel("No translated messages yet", SwingConstants.CENTER);

	TranslatedMessageLogPanel()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton clearButton = new JButton("Clear");
		clearButton.setFont(FontManager.getRunescapeSmallFont());
		clearButton.setFocusPainted(false);
		clearButton.setMargin(new java.awt.Insets(1, 6, 1, 6));
		clearButton.addActionListener(e -> clear());

		JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		headerRow.setOpaque(false);
		headerRow.add(clearButton);
		add(headerRow, BorderLayout.NORTH);

		entriesPanel.setLayout(new BoxLayout(entriesPanel, BoxLayout.Y_AXIS));
		entriesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setFont(FontManager.getRunescapeSmallFont());
		emptyLabel.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
		entriesPanel.add(emptyLabel);

		JScrollPane scrollPane = new JScrollPane(entriesPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(12);
		add(scrollPane, BorderLayout.CENTER);
	}

	void addEntry(TranslatedMessageEntry entry)
	{
		entriesPanel.remove(emptyLabel);

		// A colored left-edge accent bar (matched to the detected language's flag colors, via
		// FlagIconFactory - drawn as real shapes rather than an emoji glyph, since plain Swing
		// labels can't reliably render flag emoji either, same limitation as the in-chat sprite
		// badge) instead of a plain background box, so entries read as distinct cards rather
		// than a flat wall of text.
		JPanel row = new JPanel(new BorderLayout(8, 2));
		row.setBackground(CARD_BACKGROUND);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 6, 0),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getMaximumSize().height));

		JLabel headerLabel = new JLabel(entry.getSender());
		headerLabel.setForeground(ACCENT);
		headerLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
		if (entry.getDetectedLanguage() != null)
		{
			headerLabel.setIcon(new ImageIcon(FlagIconFactory.create(entry.getDetectedLanguage(), 16, 11)));
			headerLabel.setIconTextGap(6);
			headerLabel.setHorizontalTextPosition(SwingConstants.RIGHT);
		}

		JLabel translatedLabel = new JLabel("<html><body style='width: 150px'>" + escape(entry.getTranslatedText()) + "</body></html>");
		translatedLabel.setForeground(ColorScheme.TEXT_COLOR);
		translatedLabel.setFont(FontManager.getRunescapeSmallFont());

		JLabel originalLabel = new JLabel("<html><body style='width: 150px'>" + escape(entry.getOriginalText()) + "</body></html>");
		originalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		originalLabel.setFont(originalLabel.getFont().deriveFont(originalLabel.getFont().getSize2D() - 1f));
		originalLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));

		JPanel textPanel = new JPanel();
		textPanel.setOpaque(false);
		textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
		textPanel.add(translatedLabel);
		textPanel.add(originalLabel);

		row.add(headerLabel, BorderLayout.NORTH);
		row.add(textPanel, BorderLayout.CENTER);

		entriesPanel.add(row, 0);
		while (entriesPanel.getComponentCount() > MAX_ENTRIES)
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
