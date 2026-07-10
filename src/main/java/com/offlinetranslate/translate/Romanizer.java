package com.offlinetranslate.translate;

import com.ibm.icu.text.Transliterator;

/**
 * Converts non-Latin-script text (Arabic, Cyrillic, Devanagari, CJK, ...) into plain Latin
 * letters, for outgoing translations that need to be written into the OSRS chatbox - see
 * {@link com.offlinetranslate.Language}'s class javadoc for why that's necessary at all.
 * <p>
 * Uses ICU4J's built-in "Any-Latin" transform (a general script-to-Latin romanizer covering
 * essentially every script Unicode defines, not something hand-rolled per language), chained
 * with "Latin-ASCII" to additionally strip any combining diacritics the first step leaves behind
 * (macrons, underdots, etc. from formal transliteration schemes) down to plain a-z. That second
 * step trades transliteration precision for a hard guarantee: the output only ever contains
 * characters the OSRS chat font already renders correctly for the existing Latin-script
 * languages, rather than assuming the font's coverage extends to Unicode's wider Latin Extended
 * blocks (untested, and not worth risking a repeat of the exact "?" bug this exists to avoid).
 */
final class Romanizer
{
	// Transliterator instances are expensive to construct (rule parsing) but are documented as
	// thread-safe for transliterate() calls afterward, so one shared instance is built once
	// rather than per-call.
	private static final Transliterator INSTANCE = Transliterator.getInstance("Any-Latin; Latin-ASCII");

	private Romanizer()
	{
	}

	static String toLatin(String text)
	{
		if (text == null || text.isEmpty())
		{
			return text;
		}
		return INSTANCE.transliterate(text);
	}
}
