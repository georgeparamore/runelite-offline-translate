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
 * <p>
 * <b>Deliberately opaque with {@code setContentAreaFilled(true)}</b> (the look-and-feel default),
 * not the more obvious {@code setContentAreaFilled(false)}/{@code setOpaque(false)} - that
 * combination was tried first and confirmed live to make these buttons render as nothing at all
 * under RuneLite's actual runtime look-and-feel (text, icon, everything - not just the square
 * background), even though the exact same custom-{@code paintComponent} technique works fine for
 * plain {@link javax.swing.JLabel}s and {@link RoundedPanel} elsewhere in this panel. Rather than
 * chase why a LAF-dependent flat-button technique broke under a LAF this project doesn't control,
 * this stays opaque and lets the look-and-feel paint its own square background/text normally
 * first, then draws the rounded pill on top in {@link #paintComponent} - the small unrounded
 * corner slivers underneath end up hidden as long as {@link #fillColor} matches (or is close to)
 * the parent container's own background, which it does everywhere this is used.
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
		setBorderPainted(false);
		setMargin(new java.awt.Insets(4, 10, 4, 10));
		// Matches PanelColors.CARD - the background of LanguagePackRowPanel, the only container
		// this is used inside - so the square corners the look-and-feel fills in underneath the
		// rounded overlay (see class javadoc) blend into the row instead of showing as a visible
		// square behind the pill.
		setBackground(PanelColors.CARD);
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
