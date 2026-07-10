package com.offlinetranslate;

import java.lang.reflect.Method;

/**
 * Verifies ICU4J's "Any-Latin; Latin-ASCII" transform actually produces plain-ASCII output for
 * every non-Latin-script language this plugin supports - the whole point of romanizing outgoing
 * translations before they reach the OSRS chatbox (see {@code Language}'s class javadoc) is a
 * hard guarantee of renderability, so this fails loudly if any sample comes back with a
 * character outside 0-127. No network or downloaded model needed - this tests the
 * transliteration step in isolation, not the translation pipeline around it.
 */
public class RomanizerSmokeTest
{
	public static void main(String[] args) throws Exception
	{
		Class<?> romanizerClass = Class.forName("com.offlinetranslate.translate.Romanizer");
		Method toLatin = romanizerClass.getDeclaredMethod("toLatin", String.class);
		toLatin.setAccessible(true);

		String[][] samples = {
			{"Arabic", "مرحبا يا صديقي"},
			{"Russian", "Привет, друг мой"},
			{"Ukrainian", "Привіт, друже"},
			{"Hindi", "नमस्ते मेरे दोस्त"},
			{"Chinese", "你好我的朋友"},
			{"Japanese", "こんにちは友達"},
			{"Korean", "안녕하세요 친구"},
		};

		boolean anyNonAscii = false;
		for (String[] sample : samples)
		{
			String language = sample[0];
			String original = sample[1];
			String romanized = (String) toLatin.invoke(null, original);
			boolean nonAscii = !romanized.chars().allMatch(c -> c < 128);
			anyNonAscii |= nonAscii;
			System.out.println((nonAscii ? "[NON-ASCII] " : "[ok] ") + language + ": " + original + "  ->  " + romanized);
		}

		if (anyNonAscii)
		{
			throw new AssertionError("Romanizer produced non-ASCII output for at least one language - see [NON-ASCII] lines above");
		}
	}
}
