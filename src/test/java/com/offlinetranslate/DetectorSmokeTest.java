package com.offlinetranslate;

import com.offlinetranslate.translate.ChatLanguageDetector;

public class DetectorSmokeTest
{
	public static void main(String[] args)
	{
		ChatLanguageDetector detector = new ChatLanguageDetector();
		String[] tests = {
			"Hola", "hello", "gracias", "gg", "sup", "brb", "lol",
			"nice job", "good luck", "well done", "thank you",
			"good luck with the boss",
			"anyone want to team up for raids",
			"thanks for the help everyone",
			"where is the nearest bank",
			"I need help with the boss",
			"gracias por la ayuda con el jefe",
			"necesito ayuda para encontrar el banco"
		};
		for (String text : tests)
		{
			Language result = detector.detect(text);
			System.out.println("[" + text.length() + "] \"" + text + "\" -> " + result);
		}
	}
}
