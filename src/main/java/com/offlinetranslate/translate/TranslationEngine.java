package com.offlinetranslate.translate;

import com.offlinetranslate.Language;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and caches {@link MarianOnnxTranslator} instances per (language, direction) and runs
 * translations against them. Loading an ONNX pack is expensive (encoder + decoder + two
 * tokenizers), so translators are kept warm after first use rather than reloaded per message.
 */
@Slf4j
@Singleton
public class TranslationEngine
{
	private final ModelManager modelManager;
	private final Map<String, MarianOnnxTranslator> loaded = new ConcurrentHashMap<>();

	@Inject
	public TranslationEngine(ModelManager modelManager)
	{
		this.modelManager = modelManager;
	}

	/** Translates {@code text} in the given language into English. Requires that language's TO_ENGLISH pack to be downloaded. */
	public String translateToEnglish(String text, Language language) throws TranslationException
	{
		if (language.isEnglish())
		{
			return text;
		}
		return translate(text, language, PackDirection.TO_ENGLISH);
	}

	/** Translates {@code text} in English into the given language. Requires that language's FROM_ENGLISH pack to be downloaded (not every language has one - see {@link Language#supportsTranslationFromEnglish()}). */
	public String translateFromEnglish(String text, Language language) throws TranslationException
	{
		if (language.isEnglish())
		{
			return text;
		}
		return translate(text, language, PackDirection.FROM_ENGLISH);
	}

	/**
	 * Translates {@code text} from {@code source} to {@code target}, pivoting through English
	 * since every downloadable pack is English-paired (no direct e.g. Spanish-to-French model).
	 */
	public String translate(String text, Language source, Language target) throws TranslationException
	{
		if (source == target)
		{
			return text;
		}
		String english = translateToEnglish(text, source);
		return translateFromEnglish(english, target);
	}

	private String translate(String text, Language language, PackDirection direction) throws TranslationException
	{
		if (modelManager.getStatus(language, direction) != PackStatus.READY)
		{
			throw new TranslationException("Language pack not downloaded: " + language.getDisplayName() + " (" + direction + ")");
		}

		String key = language.getCode() + ":" + direction;
		MarianOnnxTranslator translator;
		try
		{
			translator = loaded.computeIfAbsent(key, k -> {
				try
				{
					return new MarianOnnxTranslator(modelManager.getPackDir(language, direction));
				}
				catch (TranslationException e)
				{
					throw new RuntimeException(e);
				}
			});
		}
		catch (RuntimeException e)
		{
			throw new TranslationException("Failed to load language pack for " + language.getDisplayName(), e.getCause() != null ? e.getCause() : e);
		}

		try
		{
			return translator.translate(text);
		}
		catch (RuntimeException | TranslationException e)
		{
			loaded.remove(key);
			translator.close();
			throw e instanceof TranslationException ? (TranslationException) e
				: new TranslationException("Translation failed for " + language.getDisplayName(), e);
		}
	}

	public void unload(Language language, PackDirection direction)
	{
		String key = language.getCode() + ":" + direction;
		MarianOnnxTranslator translator = loaded.remove(key);
		if (translator != null)
		{
			translator.close();
		}
	}

	public void shutdown()
	{
		loaded.values().forEach(MarianOnnxTranslator::close);
		loaded.clear();
	}
}
