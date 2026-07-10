package com.offlinetranslate.chat;

import com.offlinetranslate.Language;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Draws a small flag badge using each country's real flag colors, as plain solid bands/shapes -
 * not a rendered flag emoji glyph. Java's AWT text rendering can't reliably draw multi-codepoint
 * emoji sequences like flags (regional-indicator pairs) even on platforms with a system
 * color-emoji font; {@code Graphics2D.drawString} doesn't reliably compose them into the flag
 * glyph, in either the in-game chat font or a plain Swing {@code JLabel}. Solid shapes drawn
 * directly have no such dependency, so this is reused for both the in-chat sprite badge (sized
 * small, see {@link #create(Language)}) and the side panel's log entries (sized larger, see
 * {@link #create(Language, int, int)}).
 */
public final class FlagIconFactory
{
	private static final int CHAT_WIDTH = 11;
	private static final int CHAT_HEIGHT = 8;

	private FlagIconFactory()
	{
	}

	/** In-chat sprite size (11x8) - small enough to sit inline with a player's name. */
	public static BufferedImage create(Language language)
	{
		return create(language, CHAT_WIDTH, CHAT_HEIGHT);
	}

	public static BufferedImage create(Language language, int width, int height)
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			paintFlag(g, language, width, height);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private static void paintFlag(Graphics2D g, Language language, int width, int height)
	{
		switch (language)
		{
			case SPANISH:
				horizontalBands(g, width, height, RED, RED, YELLOW, YELLOW, RED, RED);
				break;
			case FRENCH:
				verticalBands(g, width, height, BLUE, WHITE, RED);
				break;
			case GERMAN:
				horizontalBands(g, width, height, BLACK, RED, GOLD);
				break;
			case ITALIAN:
				verticalBands(g, width, height, GREEN, WHITE, RED);
				break;
			case DUTCH:
				horizontalBands(g, width, height, RED, WHITE, BLUE);
				break;
			case POLISH:
				horizontalBands(g, width, height, WHITE, RED);
				break;
			case SWEDISH:
				nordicCross(g, width, height, BLUE, YELLOW);
				break;
			case FINNISH:
				nordicCross(g, width, height, WHITE, BLUE);
				break;
			case DANISH:
				nordicCross(g, width, height, RED, WHITE);
				break;
			case CZECH:
				czechFlag(g, width, height);
				break;
			case VIETNAMESE:
				solidWithCenterMark(g, width, height, RED, YELLOW);
				break;
			case INDONESIAN:
				horizontalBands(g, width, height, RED, WHITE);
				break;
			case HUNGARIAN:
				horizontalBands(g, width, height, RED, WHITE, GREEN);
				break;
			case AFRIKAANS:
				horizontalBands(g, width, height, GREEN, GOLD, BLACK);
				break;
			case ENGLISH:
			default:
				horizontalBands(g, width, height, BLUE, WHITE, RED);
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

	private static void horizontalBands(Graphics2D g, int width, int height, Color... colors)
	{
		int bandHeight = Math.max(1, height / colors.length);
		for (int i = 0; i < colors.length; i++)
		{
			g.setColor(colors[i]);
			int y = i * bandHeight;
			int h = (i == colors.length - 1) ? height - y : bandHeight;
			g.fillRect(0, y, width, h);
		}
	}

	private static void verticalBands(Graphics2D g, int width, int height, Color... colors)
	{
		int bandWidth = Math.max(1, width / colors.length);
		for (int i = 0; i < colors.length; i++)
		{
			g.setColor(colors[i]);
			int x = i * bandWidth;
			int w = (i == colors.length - 1) ? width - x : bandWidth;
			g.fillRect(x, 0, w, height);
		}
	}

	/** Simplified Nordic-style flag: field color with an off-center cross in the accent color. */
	private static void nordicCross(Graphics2D g, int width, int height, Color field, Color cross)
	{
		g.setColor(field);
		g.fillRect(0, 0, width, height);
		g.setColor(cross);
		int barThickness = Math.max(1, Math.round(height / 4f));
		int vBarX = width * 5 / 12;
		g.fillRect(vBarX, 0, barThickness, height);
		int hBarY = height / 2 - barThickness / 2;
		g.fillRect(0, hBarY, width, barThickness);
	}

	/** Simplified Czech flag: white/red horizontal bands with a blue wedge from the hoist side. */
	private static void czechFlag(Graphics2D g, int width, int height)
	{
		g.setColor(WHITE);
		g.fillRect(0, 0, width, height / 2);
		g.setColor(RED);
		g.fillRect(0, height / 2, width, height - height / 2);
		g.setColor(BLUE);
		int[] xs = {0, width * 2 / 5, 0};
		int[] ys = {0, height / 2, height};
		g.fillPolygon(xs, ys, 3);
	}

	/** Simplified flag with a solid field and a small centered accent mark (e.g. Vietnam's star, simplified to a dot). */
	private static void solidWithCenterMark(Graphics2D g, int width, int height, Color field, Color mark)
	{
		g.setColor(field);
		g.fillRect(0, 0, width, height);
		g.setColor(mark);
		int size = Math.max(2, Math.min(width, height) / 3);
		g.fillOval(width / 2 - size / 2, height / 2 - size / 2, size, size);
	}
}
