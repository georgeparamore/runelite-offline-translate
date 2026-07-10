package com.offlinetranslate;

/**
 * Supported languages and the Xenova ONNX-converted OPUS-MT model repos backing them.
 * <p>
 * Model availability is asymmetric in upstream Helsinki-NLP/OPUS-MT: some languages only have
 * a to-English model (no English back-translation model exists), and Japanese uses a different
 * source-language code ("jap") for the English-to-Japanese direction than the to-English
 * direction ("ja") - that's not a typo, it reflects the actual upstream repo names.
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
	RUSSIAN("ru", "Russian", "🇷🇺", "ru", "ru"),
	CHINESE("zh", "Chinese", "🇨🇳", "zh", "zh"),
	ARABIC("ar", "Arabic", "🇸🇦", "ar", "ar"),
	JAPANESE("ja", "Japanese", "🇯🇵", "ja", "jap"),
	KOREAN("ko", "Korean", "🇰🇷", "ko", null),
	POLISH("pl", "Polish", "🇵🇱", "pl", null),
	SWEDISH("sv", "Swedish", "🇸🇪", "sv", "sv"),
	FINNISH("fi", "Finnish", "🇫🇮", "fi", "fi"),
	DANISH("da", "Danish", "🇩🇰", "da", "da"),
	CZECH("cs", "Czech", "🇨🇿", "cs", "cs"),
	HINDI("hi", "Hindi", "🇮🇳", "hi", "hi"),
	VIETNAMESE("vi", "Vietnamese", "🇻🇳", "vi", "vi"),
	UKRAINIAN("uk", "Ukrainian", "🇺🇦", "uk", "uk"),
	INDONESIAN("id", "Indonesian", "🇮🇩", "id", "id"),
	HUNGARIAN("hu", "Hungarian", "🇭🇺", "hu", "hu"),
	AFRIKAANS("af", "Afrikaans", "🇿🇦", "af", "af");

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

	Language(String code, String displayName, String flagEmoji, String toEnglishModelCode, String fromEnglishModelCode)
	{
		this.code = code;
		this.displayName = displayName;
		this.flagEmoji = flagEmoji;
		this.toEnglishModelCode = toEnglishModelCode;
		this.fromEnglishModelCode = fromEnglishModelCode;
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
