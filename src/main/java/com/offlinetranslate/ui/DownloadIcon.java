package com.offlinetranslate.ui;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * A small hand-drawn down-arrow icon (shaft + arrowhead), used for the "download all" section
 * action instead of a text button. Not a Unicode glyph (e.g. "⬇") - the RuneScape bitmap font
 * this panel uses elsewhere is a custom font covering game-relevant Latin text, and there's no
 * guarantee it has Unicode Dingbats coverage any more than it had flag-emoji coverage (the exact
 * problem {@link FlagIconFactory} already exists to work around). Drawing the shape directly
 * has no such dependency.
 */
class DownloadIcon implements Icon
{
	private static final int SIZE = 14;

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(c.isEnabled() ? PanelColors.ACCENT : PanelColors.TEXT_MUTED);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

			int midX = x + SIZE / 2;
			g2.drawLine(midX, y + 1, midX, y + SIZE - 5);
			int[] xs = {midX - 4, midX, midX + 4};
			int[] ys = {y + SIZE - 9, y + SIZE - 3, y + SIZE - 9};
			g2.drawPolyline(xs, ys, 3);
			g2.drawLine(x + 1, y + SIZE - 1, x + SIZE - 1, y + SIZE - 1);
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
