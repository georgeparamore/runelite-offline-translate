package com.offlinetranslate.ui;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;
import javax.swing.JCheckBox;

/**
 * A small rounded checkbox icon matching the redesigned panel's palette - a filled violet
 * square with a white checkmark when selected, an outlined square otherwise. The default Swing
 * checkbox icon draws its checkmark in a look-and-feel-dependent dark color that isn't visible
 * against this panel's near-black background (confirmed live: checked boxes looked identical to
 * unchecked ones), and there's no simple property to recolor just the checkmark - painting the
 * whole icon ourselves sidesteps that instead of fighting the look and feel.
 */
class CheckboxIcon implements Icon
{
	private static final int SIZE = 14;

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			boolean selected = c instanceof JCheckBox && ((JCheckBox) c).isSelected();

			RoundRectangle2D box = new RoundRectangle2D.Float(x, y, SIZE, SIZE, 4, 4);
			if (selected)
			{
				g2.setColor(PanelColors.ACCENT);
				g2.fill(box);
			}
			else
			{
				g2.setColor(PanelColors.CARD_ALT);
				g2.fill(box);
				g2.setColor(PanelColors.TEXT_MUTED);
				g2.draw(box);
			}

			if (selected)
			{
				g2.setColor(PanelColors.TEXT);
				g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
				int[] xs = {x + 3, x + 6, x + SIZE - 3};
				int[] ys = {y + 7, y + 10, y + 4};
				g2.drawPolyline(xs, ys, 3);
			}
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
