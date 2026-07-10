package com.offlinetranslate.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;

/** Scrollable log of incoming chat messages that have been translated for you. */
class TranslatedMessageLogPanel extends JPanel
{
	private static final int MAX_ENTRIES = 50;

	private final JPanel entriesPanel = new JPanel();

	TranslatedMessageLogPanel()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		entriesPanel.setLayout(new BoxLayout(entriesPanel, BoxLayout.Y_AXIS));
		entriesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(entriesPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(12);
		add(scrollPane, BorderLayout.CENTER);
	}

	void addEntry(TranslatedMessageEntry entry)
	{
		JPanel row = new JPanel(new BorderLayout(2, 2));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 4, 0),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));

		String header = (entry.getDetectedLanguage() != null ? entry.getDetectedLanguage().getFlagEmoji() + " " : "") + entry.getSender();
		JLabel headerLabel = new JLabel(header);
		headerLabel.setForeground(ColorScheme.BRAND_ORANGE);

		JLabel translatedLabel = new JLabel("<html><body style='width: 160px'>" + escape(entry.getTranslatedText()) + "</body></html>");
		translatedLabel.setForeground(ColorScheme.TEXT_COLOR);

		JLabel originalLabel = new JLabel("<html><body style='width: 160px'>" + escape(entry.getOriginalText()) + "</body></html>");
		originalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		originalLabel.setFont(originalLabel.getFont().deriveFont(originalLabel.getFont().getSize2D() - 1f));

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

	private static String escape(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
