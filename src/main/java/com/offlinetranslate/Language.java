package com.offlinetranslate;

/**
 * Supported languages and the Xenova ONNX-converted OPUS-MT model repos backing them.
 * <p>
 * Model availability is asymmetric in upstream Helsinki-NLP/OPUS-MT: some languages only have
 * a to-English model (no English back-translation model exists).
 * <p>
 * <b>Non-Latin-script languages and the OSRS font problem.</b> The OSRS client's own chat font
 * only has glyphs for English plus accented European characters (é, ñ, ü, and similar) -
 * confirmed live: a correctly translated Arabic message ("hi" -> "مرحباً", verified correct in
 * the console) still rendered as "?" in the actual game chat, because the game engine itself has
 * no glyphs to draw it with, not because of anything wrong with the translation. That's only a
 * problem for the *outgoing* direction, though: an incoming foreign message gets translated for
 * display in this plugin's own side panel, which is ordinary Java-rendered text and has no such
 * limitation - the font gap only bites when translated text has to be written into the actual
 * OSRS chatbox to be sent. So rather than removing these languages outright (their original
 * treatment), {@link #usesLatinScript()} marks which ones need romanizing before being written
 * to the chatbox - see {@code TranslationEngine.translateFromEnglish()} for where that happens,
 * via ICU4J's "Any-Latin; Latin-ASCII" transform (e.g. Arabic "مرحبا" -> "mrhba"). It's a lossy
 * approximation, not a proper transliteration scheme, but it's guaranteed renderable, which
 * native script text in this client never was.
 */
public enum Language
{
	/** Not a real model pack - the pivot language. No files are downloaded for this entry. */
	ENGLISH("en", "English", "🇬🇧", null, null),
	SPANISH("es", "Spanish", "🇪🇸", "es", "es"),
	FRENCH("fr", "French", "🇫🇷", "fr", "fr"),
	GERMAN("de", "German", "🇩🇪", "de", "de"),
	ITALIAN("it", "Italian", "🇮🇹", "it", "it"),
	DUTCH("nl", "Dutch", "🇳🇱", "nl", "nl"),
	POLISH("pl", "Polish", "🇵🇱", "pl", null),
	SWEDISH("sv", "Swedish", "🇸🇪", "sv", "sv"),
	FINNISH("fi", "Finnish", "🇫🇮", "fi", "fi"),
	DANISH("da", "Danish", "🇩🇰", "da", "da"),
	CZECH("cs", "Czech", "🇨🇿", "cs", "cs"),
	// Latin-script but diacritic-heavy (tone marks) - kept in, but more likely than the others
	// here to hit characters outside the client font's coverage. Untested either way.
	VIETNAMESE("vi", "Vietnamese", "🇻🇳", "vi", "vi"),
	INDONESIAN("id", "Indonesian", "🇮🇩", "id", "id"),
	HUNGARIAN("hu", "Hungarian", "🇭🇺", "hu", "hu"),
	AFRIKAANS("af", "Afrikaans", "🇿🇦", "af", "af"),
	// Non-Latin-script - see the class javadoc. Detection and incoming (->English) translation
	// work exactly like any other language; outgoing (English->) gets romanized before it's
	// written to the chatbox. Repo names verified live against huggingface.co (not guessed) -
	// Japanese in particular has an asymmetric, easy-to-get-wrong upstream naming quirk: the
	// to-English model repo uses "ja" but the from-English one uses "jap".
	ARABIC("ar", "Arabic", "🇸🇦", "ar", "ar", false),
	RUSSIAN("ru", "Russian", "🇷🇺", "ru", "ru", false),
	UKRAINIAN("uk", "Ukrainian", "🇺🇦", "uk", "uk", false),
	HINDI("hi", "Hindi", "🇮🇳", "hi", "hi", false),
	CHINESE("zh", "Chinese", "🇨🇳", "zh", "zh", false),
	JAPANESE("ja", "Japanese", "🇯🇵", "ja", "jap", false),
	// No English->Korean model available upstream (Xenova/opus-mt-en-ko/-kor don't exist,
	// confirmed live) - same asymmetric situation as Polish, just for a non-Latin script.
	KOREAN("ko", "Korean", "🇰🇷", "ko", null, false);

	/** ISO 639-1-ish code used by the offline language detector and as this enum's stable id. */
	private final String code;
	private final String displayName;
	private final String flagEmoji;
	/** Source-language code in the "Xenova/opus-mt-{code}-en" to-English model repo name. */
	private final String toEnglishModelCode;
	/**
	 * Target-language code in the "Xenova/opus-mt-en-{code}" from-English model repo name, or
	 * null if no such model exists upstream (that language can be detected/translated to
	 * English, but not selected as an outgoing translation target).
	 */
	private final String fromEnglishModelCode;
	private final boolean usesLatinScript;

	Language(String code, String displayName, String flagEmoji, String toEnglishModelCode, String fromEnglishModelCode)
	{
		this(code, displayName, flagEmoji, toEnglishModelCode, fromEnglishModelCode, true);
	}

	Language(String code, String displayName, String flagEmoji, String toEnglishModelCode, String fromEnglishModelCode, boolean usesLatinScript)
	{
		this.code = code;
		this.displayName = displayName;
		this.flagEmoji = flagEmoji;
		this.toEnglishModelCode = toEnglishModelCode;
		this.fromEnglishModelCode = fromEnglishModelCode;
		this.usesLatinScript = usesLatinScript;
	}

	public String getCode()
	{
		return code;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public String getFlagEmoji()
	{
		return flagEmoji;
	}

	public boolean isEnglish()
	{
		return this == ENGLISH;
	}

	/** False for languages whose native script the OSRS chat font can't render - see the class javadoc. Outgoing translations into these get romanized before being written to the chatbox. */
	public boolean usesLatinScript()
	{
		return usesLatinScript;
	}

	public boolean supportsTranslationFromEnglish()
	{
		return isEnglish() || fromEnglishModelCode != null;
	}

	public String toEnglishRepo()
	{
		if (toEnglishModelCode == null)
		{
			throw new IllegalStateException(displayName + " has no model repo (it's the pivot language)");
		}
		return "Xenova/opus-mt-" + toEnglishModelCode + "-en";
	}

	public String fromEnglishRepo()
	{
		if (fromEnglishModelCode == null)
		{
			throw new IllegalStateException(displayName + " has no English->" + displayName + " model available");
		}
		return "Xenova/opus-mt-en-" + fromEnglishModelCode;
	}

	public static Language fromCode(String code)
	{
		for (Language language : values())
		{
			if (language.code.equals(code))
			{
				return language;
			}
		}
		return null;
	}

	/** Used directly by Swing combo box rendering - without this override they'd show the raw enum constant name (e.g. "INDONESIAN") instead of a flag and proper display name. */
	@Override
	public String toString()
	{
		return flagEmoji + "  " + displayName;
	}
}
