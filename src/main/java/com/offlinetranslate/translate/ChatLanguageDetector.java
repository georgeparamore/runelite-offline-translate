package com.offlinetranslate.translate;

import com.google.common.base.Optional;
import com.offlinetranslate.Language;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObject;
import com.optimaize.langdetect.text.TextObjectFactory;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Offline chat-language detection. Unlike translation, this needs no downloaded model - the
 * language-detector library ships its n-gram profiles in its own jar, so detection works
 * immediately with no language pack required.
 * <p>
 * OSRS chat lines are short (max ~80 characters after the game's own truncation, often just a
 * few words), which is the hard case for statistical language ID - short text gives the n-gram
 * model much less signal than the paragraph-length input it's tuned for. Treat detections on
 * very short messages as low-confidence; {@link #detect(String)} already filters out the
 * lowest-confidence guesses, but it will still be wrong more often on a 2-word message than a
 * full sentence.
 */
@Slf4j
@Singleton
public class ChatLanguageDetector
{
	private final LanguageDetector languageDetector;
	private final TextObjectFactory textObjectFactory;

	@Inject
	public ChatLanguageDetector()
	{
		LanguageDetector detector;
		try
		{
			List<LanguageProfile> profiles = new LanguageProfileReader().readAllBuiltIn();
			detector = LanguageDetectorBuilder.create(NgramExtractors.standard())
				.withProfiles(profiles)
				.build();
		}
		catch (IOException e)
		{
			log.warn("Failed to load language-detector profiles; auto-detect will be disabled", e);
			detector = null;
		}
		this.languageDetector = detector;
		this.textObjectFactory = CommonTextObjectFactories.forDetectingShortCleanText();
	}

	/**
	 * @return the detected {@link Language} (which may be {@link Language#ENGLISH}), or null if
	 * detection is unavailable, the text is too ambiguous to classify confidently, or the
	 * detected language isn't in the supported list at all.
	 */
	public Language detect(String text)
	{
		if (languageDetector == null || text == null || text.trim().length() < 3)
		{
			return null;
		}

		TextObject textObject = textObjectFactory.forText(text);
		Optional<LdLocale> result = languageDetector.detect(textObject);
		if (!result.isPresent())
		{
			return null;
		}

		return Language.fromCode(result.get().getLanguage());
	}
}
