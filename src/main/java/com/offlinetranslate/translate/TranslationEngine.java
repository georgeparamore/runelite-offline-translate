package com.offlinetranslate.translate;

import com.offlinetranslate.Language;
import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.model.PackStatus;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	private final ExecutorService warmUpExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "offline-translate-warmup");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public TranslationEngine(ModelManager modelManager)
	{
		this.modelManager = modelManager;
	}

	/**
	 * True if this (language, direction) is already loaded and ready to translate near-
	 * instantly. First-time loading (ONNX sessions + native SentencePiece library) takes
	 * seconds, not milliseconds - callers on a latency-sensitive path (e.g. a keypress handler
	 * that has to finish before the game's own input handling continues) should check this and
	 * use {@link #warmUp} instead of calling translate() cold. Confirmed the hard way: doing a
	 * multi-second synchronous translate() inside a chat keypress handler corrupted the OSRS
	 * client's own send state rather than just being slow.
	 */
	public boolean isWarm(Language language, PackDirection direction)
	{
		return language.isEnglish() || loaded.containsKey(language.getCode() + ":" + direction);
	}

	/** Loads a (language, direction) translator on a background thread, if its pack is downloaded and it isn't already loaded. Fire-and-forget - check {@link #isWarm} to know when it's done. */
	public void warmUp(Language language, PackDirection direction)
	{
		if (language.isEnglish() || isWarm(language, direction) || modelManager.getStatus(language, direction) != PackStatus.READY)
		{
			return;
		}
		warmUpExecutor.submit(() -> {
			try
			{
				translate("warm up", language, direction);
			}
			catch (TranslationException e)
			{
				log.debug("Warm-up failed for {} ({})", language, direction, e);
			}
		});
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
		warmUpExecutor.shutdownNow();
		loaded.values().forEach(MarianOnnxTranslator::close);
		loaded.clear();
	}
}
