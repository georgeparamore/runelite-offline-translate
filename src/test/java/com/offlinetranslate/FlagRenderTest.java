package com.offlinetranslate;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;

public class FlagRenderTest
{
	public static void main(String[] args) throws Exception
	{
		Class<?> factoryClass = Class.forName("com.offlinetranslate.chat.FlagIconFactory");
		Method create = factoryClass.getDeclaredMethod("create", Language.class);
		create.setAccessible(true);

		Language[] languages = Language.values();
		int scale = 8;
		int cols = 3;
		int rows = (int) Math.ceil(languages.length / (double) cols);
		int cellW = 16 * scale + 20;
		int cellH = 11 * scale + 24;
		BufferedImage sheet = new BufferedImage(cellW * cols, cellH * rows, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new java.awt.Color(40, 40, 40));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

		for (int i = 0; i < languages.length; i++)
		{
			BufferedImage flag = (BufferedImage) create.invoke(null, languages[i]);
			int col = i % cols;
			int row = i / cols;
			int x = col * cellW + 10;
			int y = row * cellH + 10;
			g.drawImage(flag, x, y, 16 * scale, 11 * scale, null);
			g.setColor(java.awt.Color.WHITE);
			g.drawString(languages[i].getDisplayName(), x, y + 11 * scale + 14);
		}
		g.dispose();

		File out = new File("/tmp/flag-preview.png");
		ImageIO.write(sheet, "png", out);
		System.out.println("Wrote " + out.getAbsolutePath());
	}
}
