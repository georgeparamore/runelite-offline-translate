package com.offlinetranslate.chat;

import com.offlinetranslate.Language;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Draws a small in-chat flag badge using each country's real flag colors, as plain solid
 * bands/shapes - not a rendered flag emoji glyph. Java's AWT text rendering can't reliably
 * draw multi-codepoint emoji sequences like flags (regional-indicator pairs) even on platforms
 * with a system color-emoji font; {@code Graphics2D.drawString} doesn't reliably compose them
 * into the flag glyph. Solid shapes drawn directly have no such dependency.
 */
final class FlagIconFactory
{
	private static final int WIDTH = 11;
	private static final int HEIGHT = 8;

	private FlagIconFactory()
	{
	}

	static BufferedImage create(Language language)
	{
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			paintFlag(g, language);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private static void paintFlag(Graphics2D g, Language language)
	{
		switch (language)
		{
			case SPANISH:
				horizontalBands(g, RED, RED, YELLOW, YELLOW, RED, RED);
				break;
			case FRENCH:
				verticalBands(g, BLUE, WHITE, RED);
				break;
			case GERMAN:
				horizontalBands(g, BLACK, RED, GOLD);
				break;
			case ITALIAN:
				verticalBands(g, GREEN, WHITE, RED);
				break;
			case DUTCH:
				horizontalBands(g, RED, WHITE, BLUE);
				break;
			case POLISH:
				horizontalBands(g, WHITE, RED);
				break;
			case SWEDISH:
				nordicCross(g, BLUE, YELLOW);
				break;
			case FINNISH:
				nordicCross(g, WHITE, BLUE);
				break;
			case DANISH:
				nordicCross(g, RED, WHITE);
				break;
			case CZECH:
				czechFlag(g);
				break;
			case VIETNAMESE:
				solidWithCenterMark(g, RED, YELLOW);
				break;
			case INDONESIAN:
				horizontalBands(g, RED, WHITE);
				break;
			case HUNGARIAN:
				horizontalBands(g, RED, WHITE, GREEN);
				break;
			case AFRIKAANS:
				horizontalBands(g, GREEN, GOLD, BLACK);
				break;
			case ENGLISH:
			default:
				horizontalBands(g, BLUE, WHITE, RED);
				break;
		}
	}

	private static final Color RED = new Color(0xCE, 0x11, 0x26);
	private static final Color BLUE = new Color(0x00, 0x2B, 0x7F);
	private static final Color WHITE = Color.WHITE;
	private static final Color BLACK = new Color(0x1a, 0x1a, 0x1a);
	private static final Color GOLD = new Color(0xFF, 0xCE, 0x00);
	private static final Color YELLOW = new Color(0xFF, 0xC4, 0x00);
	private static final Color GREEN = new Color(0x00, 0x8C, 0x45);

	private static void horizontalBands(Graphics2D g, Color... colors)
	{
		int bandHeight = Math.max(1, HEIGHT / colors.length);
		for (int i = 0; i < colors.length; i++)
		{
			g.setColor(colors[i]);
			int y = i * bandHeight;
			int h = (i == colors.length - 1) ? HEIGHT - y : bandHeight;
			g.fillRect(0, y, WIDTH, h);
		}
	}

	private static void verticalBands(Graphics2D g, Color... colors)
	{
		int bandWidth = Math.max(1, WIDTH / colors.length);
		for (int i = 0; i < colors.length; i++)
		{
			g.setColor(colors[i]);
			int x = i * bandWidth;
			int w = (i == colors.length - 1) ? WIDTH - x : bandWidth;
			g.fillRect(x, 0, w, HEIGHT);
		}
	}

	/** Simplified Nordic-style flag: field color with an off-center cross in the accent color. */
	private static void nordicCross(Graphics2D g, Color field, Color cross)
	{
		g.setColor(field);
		g.fillRect(0, 0, WIDTH, HEIGHT);
		g.setColor(cross);
		int vBarX = WIDTH * 5 / 12;
		g.fillRect(vBarX, 0, 2, HEIGHT);
		int hBarY = HEIGHT / 2 - 1;
		g.fillRect(0, hBarY, WIDTH, 2);
	}

	/** Simplified Czech flag: white/red horizontal bands with a blue wedge from the hoist side. */
	private static void czechFlag(Graphics2D g)
	{
		g.setColor(WHITE);
		g.fillRect(0, 0, WIDTH, HEIGHT / 2);
		g.setColor(RED);
		g.fillRect(0, HEIGHT / 2, WIDTH, HEIGHT - HEIGHT / 2);
		g.setColor(BLUE);
		int[] xs = {0, WIDTH * 2 / 5, 0};
		int[] ys = {0, HEIGHT / 2, HEIGHT};
		g.fillPolygon(xs, ys, 3);
	}

	/** Simplified flag with a solid field and a small centered accent mark (e.g. Vietnam's star, simplified to a dot). */
	private static void solidWithCenterMark(Graphics2D g, Color field, Color mark)
	{
		g.setColor(field);
		g.fillRect(0, 0, WIDTH, HEIGHT);
		g.setColor(mark);
		int size = 3;
		g.fillOval(WIDTH / 2 - size / 2, HEIGHT / 2 - size / 2, size, size);
	}
}
