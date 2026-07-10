package com.offlinetranslate;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Draws the sidebar nav-button icon at runtime instead of bundling a PNG asset. */
final class PluginIconFactory
{
	private PluginIconFactory()
	{
	}

	static BufferedImage create()
	{
		int size = 16;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(66, 135, 245));
			g.fillOval(0, 0, size - 1, size - 1);
			g.setColor(Color.WHITE);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
			String text = "T";
			int textWidth = g.getFontMetrics().stringWidth(text);
			g.drawString(text, (size - textWidth) / 2, size - 4);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
