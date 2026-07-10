package com.offlinetranslate.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JButton;
import net.runelite.client.ui.FontManager;

/**
 * A {@link JButton} painted as a filled, fully-rounded pill instead of Swing's default
 * square-cornered button - the visual language the side panel redesign is built around.
 * {@code setContentAreaFilled(false)}/{@code setBorderPainted(false)} turn off the look-and-feel's
 * own square background/border so this can paint its own rounded one underneath the inherited
 * text-drawing behavior instead of fighting it.
 */
class PillButton extends JButton
{
	private Color fillColor;
	private final Color textColor;

	PillButton(String text, Color fillColor, Color textColor)
	{
		super(text);
		this.fillColor = fillColor;
		this.textColor = textColor;
		setFont(FontManager.getRunescapeSmallFont());
		setForeground(textColor);
		setFocusPainted(false);
		setContentAreaFilled(false);
		setBorderPainted(false);
		setOpaque(false);
		setMargin(new java.awt.Insets(4, 10, 4, 10));
	}

	void setFillColor(Color fillColor)
	{
		this.fillColor = fillColor;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Color background = fillColor;
			if (!isEnabled())
			{
				background = new Color(background.getRed(), background.getGreen(), background.getBlue(), 90);
			}
			else if (getModel().isPressed())
			{
				background = background.darker();
			}
			else if (getModel().isRollover())
			{
				background = background.brighter();
			}
			g2.setColor(background);
			float h = getHeight();
			g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, h, h));
		}
		finally
		{
			g2.dispose();
		}
		setForeground(isEnabled() ? textColor : new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 160));
		super.paintComponent(g);
	}
}
