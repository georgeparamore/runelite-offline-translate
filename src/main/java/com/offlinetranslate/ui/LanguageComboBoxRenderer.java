package com.offlinetranslate.ui;

import com.offlinetranslate.Language;
import com.offlinetranslate.chat.FlagIconFactory;
import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JList;

/**
 * Draws each {@link Language} combo box entry (both the closed box's selected value and the
 * open dropdown's rows) with a {@link FlagIconFactory} image icon and plain display name,
 * instead of relying on {@link Language#toString()}'s flag emoji character - confirmed live: a
 * plain {@code JComboBox<Language>} using the default renderer produced garbled text ("Inglish"
 * instead of "English"), because Swing can't reliably render multi-codepoint flag emoji any more
 * than it could in the chat log or the pack list rows, both of which already moved to this same
 * drawn-icon approach for the same reason.
 */
class LanguageComboBoxRenderer extends DefaultListCellRenderer
{
	@Override
	public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
	{
		super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
		if (value instanceof Language)
		{
			Language language = (Language) value;
			setText(language.getDisplayName());
			setIcon(new ImageIcon(FlagIconFactory.create(language, 16, 11)));
			setIconTextGap(8);
		}
		if (!isSelected)
		{
			setBackground(PanelColors.CARD);
			setForeground(PanelColors.TEXT);
		}
		return this;
	}
}
