package com.offlinetranslate.chat;

import com.offlinetranslate.Language;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Draws a small in-chat badge for a language (background color + 2-letter code) instead of
 * trying to render the language's flag emoji glyph directly. Java's AWT text rendering can't
 * reliably draw color emoji on every platform/JRE (headless Linux JVMs in particular often
 * lack a color emoji font, rendering flag emoji as blank boxes), so this draws something that
 * will render correctly everywhere instead.
 */
final class FlagIconFactory
{
	private FlagIconFactory()
	{
	}

	static BufferedImage create(Language language)
	{
		int width = 16;
		int height = 11;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(colorFor(language));
			g.fillRoundRect(0, 0, width, height, 3, 3);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
			g.setColor(Color.WHITE);
			String code = language.getCode().toUpperCase();
			int textWidth = g.getFontMetrics().stringWidth(code);
			g.drawString(code, Math.max(0, (width - textWidth) / 2), height - 2);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private static Color colorFor(Language language)
	{
		int hash = language.getCode().hashCode();
		float hue = (Math.abs(hash) % 360) / 360f;
		return Color.getHSBColor(hue, 0.55f, 0.65f);
	}
}
