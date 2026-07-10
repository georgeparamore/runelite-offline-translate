package com.offlinetranslate.ui;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** A small hand-drawn trash-can icon, for the same reason {@link DownloadIcon} exists rather than using a Unicode glyph like "🗑". */
class TrashIcon implements Icon
{
	private static final int SIZE = 12;

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(PanelColors.TEXT_MUTED);
			g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

			// Lid
			g2.drawLine(x + 1, y + 2, x + SIZE - 1, y + 2);
			g2.drawLine(x + 4, y + 2, x + 4, y);
			g2.drawLine(x + SIZE - 4, y + 2, x + SIZE - 4, y);
			g2.drawLine(x + 4, y, x + SIZE - 4, y);
			// Body
			g2.drawLine(x + 2, y + 3, x + 3, y + SIZE - 1);
			g2.drawLine(x + SIZE - 2, y + 3, x + SIZE - 3, y + SIZE - 1);
			g2.drawLine(x + 3, y + SIZE - 1, x + SIZE - 3, y + SIZE - 1);
		}
		finally
		{
			g2.dispose();
		}
	}

	@Override
	public int getIconWidth()
	{
		return SIZE;
	}

	@Override
	public int getIconHeight()
	{
		return SIZE;
	}
}
