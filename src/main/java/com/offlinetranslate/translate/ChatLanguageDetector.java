package com.offlinetranslate.translate;

import com.offlinetranslate.Language;
import com.optimaize.langdetect.DetectedLanguage;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
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
 * single word), which is the hard case for statistical language ID - short text gives the
 * n-gram model much less signal than the paragraph-length input it's tuned for. {@link
 * #detect(String)} takes the library's single best guess unconditionally (see the method body
 * for why) rather than only trusting high-confidence results, so it favors actually detecting
 * short messages over refusing to guess - expect it to be wrong more often on a one-word
 * message than a full sentence, not to return null for one.
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
	 * detection is unavailable or the detected language isn't in the supported list at all.
	 */
	public Language detect(String text)
	{
		if (languageDetector == null || text == null || text.trim().length() < 3)
		{
			return null;
		}

		// Deliberately getProbabilities() + take the top result, not detect(). Confirmed live:
		// detect() returned absent for a plain one-word "Hola" - a real OSRS chat message, not
		// an edge case - because its confidence bar is tuned for paragraph-length text, not
		// chat lines. The library's own javadoc on detect() says as much: "you may want to use
		// getProbabilities() instead. This here is very strict, and sometimes returns absent
		// even though the first choice in getProbabilities() is correct." Trading some false
		// positives on genuinely ambiguous short text for actually detecting the common case.
		TextObject textObject = textObjectFactory.forText(text);
		List<DetectedLanguage> probabilities = languageDetector.getProbabilities(textObject);
		if (probabilities.isEmpty())
		{
			return null;
		}

		return Language.fromCode(probabilities.get(0).getLocale().getLanguage());
	}
}
