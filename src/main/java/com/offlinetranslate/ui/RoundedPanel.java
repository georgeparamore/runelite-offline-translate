package com.offlinetranslate.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;

/**
 * A plain {@link JPanel} that paints a rounded-rectangle background instead of a hard-edged one.
 * Swing has no built-in way to do this - {@code setBorder(new LineBorder(...))} etc. all paint
 * square corners - so this exists purely to get the rounded-card look used throughout the
 * redesigned side panel. Kept deliberately minimal: no shadow, no border stroke, just a filled
 * rounded rect, since RuneLite's sidebar is small enough that anything fancier reads as noise.
 */
class RoundedPanel extends JPanel
{
	private final int cornerRadius;
	private Color fillColor;

	RoundedPanel(int cornerRadius, Color fillColor)
	{
		this.cornerRadius = cornerRadius;
		this.fillColor = fillColor;
		setOpaque(false);
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
			g2.setColor(fillColor);
			g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), cornerRadius, cornerRadius));
		}
		finally
		{
			g2.dispose();
		}
		super.paintComponent(g);
	}
}
