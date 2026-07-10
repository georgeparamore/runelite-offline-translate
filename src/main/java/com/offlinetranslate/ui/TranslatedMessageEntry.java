package com.offlinetranslate.ui;

import com.offlinetranslate.Language;

public class TranslatedMessageEntry
{
	private final String sender;
	private final String originalText;
	private final String translatedText;
	private final Language detectedLanguage;

	public TranslatedMessageEntry(String sender, String originalText, String translatedText, Language detectedLanguage)
	{
		this.sender = sender;
		this.originalText = originalText;
		this.translatedText = translatedText;
		this.detectedLanguage = detectedLanguage;
	}

	public String getSender()
	{
		return sender;
	}

	public String getOriginalText()
	{
		return originalText;
	}

	public String getTranslatedText()
	{
		return translatedText;
	}

	public Language getDetectedLanguage()
	{
		return detectedLanguage;
	}
}
