package com.offlinetranslate.translate;

import com.offlinetranslate.Language;
import com.optimaize.langdetect.DetectedLanguage;
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
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Offline chat-language detection. Unlike translation, this needs no downloaded model - the
 * language-detector library ships its n-gram profiles in its own jar, so detection works
 * immediately with no language pack required.
 * <p>
 * <b>Short text is not just lower-accuracy here, it's actively wrong, confidently.</b>
 * Confirmed live with a battery of real short phrases: "good luck" -&gt; Polish at 92%,
 * "nice job" -&gt; Polish at 60%, "well done" -&gt; Dutch at 85%, "hello" -&gt; Italian at 60%,
 * "Hola amigo" -&gt; Italian at 80% - all wrong, none of them hedged or low-confidence, this
 * isn't a "detector unsure, defaults reasonably" situation. That's a known failure mode of
 * n-gram-based language ID on short input: with too few n-grams to work with, a completely
 * unrelated language's profile can spuriously look like the closest match, and the algorithm
 * has no way to know it's guessing. The exact same battery of test phrases, once extended to
 * full-sentence length (23+ characters), hit 99.99%+ correct in every case tried - the model
 * genuinely works well, just not on short input. So {@link #detect(String)} requires a real
 * minimum length before attempting detection at all, rather than trying to salvage short input
 * with a confidence/margin heuristic - the wrong answers above were often *more* "confident"
 * than correct ones elsewhere, so confidence alone doesn't distinguish real detections from
 * spurious ones here.
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
			// readAllBuiltIn() (70 languages) was confirmed live to actively misdetect short
			// chat-length text: "Hola" -> Turkish at 61% confidence, "Hola amigo" -> Somali at
			// 96%, "hello" -> Breton at 72%, all wrong, all high-"confidence" - a well-known
			// failure mode of n-gram detection on short text, where with too few n-grams to
			// work with, an obscure unrelated language's profile can spuriously look like the
			// closest match. Restricting the candidate set to only the languages this plugin
			// actually supports removes that noise (Breton/Somali/Turkish/etc. are never
			// competing for the guess), which is the standard mitigation for this problem.
			List<LdLocale> supportedLocales = new ArrayList<>();
			for (Language language : Language.values())
			{
				supportedLocales.add(LdLocale.fromString(language.getCode()));
			}
			List<LanguageProfile> profiles = new LanguageProfileReader().readBuiltIn(supportedLocales);
			System.err.println("[Offline Translate] language-detector loaded " + profiles.size() + " profiles");
			detector = LanguageDetectorBuilder.create(NgramExtractors.standard())
				.withProfiles(profiles)
				.build();
		}
		catch (IOException e)
		{
			// printStackTrace(), not log.warn(): SLF4J was observed defaulting to a no-op
			// logger under the plain JavaExec launch, silently swallowing exactly this kind of
			// diagnostic - this failure mode (profiles fail to load -> languageDetector stays
			// null -> detect() always returns null unconditionally, for every message) would
			// otherwise look identical to "detection ran but wasn't confident enough."
			System.err.println("[Offline Translate] Failed to load language-detector profiles - auto-detect will be disabled:");
			e.printStackTrace();
			detector = null;
		}
		this.languageDetector = detector;
		this.textObjectFactory = CommonTextObjectFactories.forDetectingShortCleanText();
	}

	/**
	 * Below this, detection was confirmed live to be actively wrong (not just low-confidence)
	 * more often than not - see the class javadoc for the actual test data. This isn't a tuned
	 * precise boundary, just comfortably above the 9-23 character range where things broke:
	 * every real-sentence-length phrase tried (23+ characters) came back 99.99%+ correct.
	 */
	private static final int MIN_LENGTH_FOR_DETECTION = 20;

	/**
	 * @return the detected {@link Language} (which may be {@link Language#ENGLISH}), or null if
	 * detection is unavailable, the text is shorter than {@link #MIN_LENGTH_FOR_DETECTION}, or
	 * the detected language isn't in the supported list at all.
	 */
	public Language detect(String text)
	{
		if (languageDetector == null || text == null || text.trim().length() < MIN_LENGTH_FOR_DETECTION)
		{
			return null;
		}

		// getProbabilities(), not detect(): the library's own javadoc on detect() recommends
		// this ("very strict, and sometimes returns absent even though the first choice in
		// getProbabilities() is correct"). Safe to take the top result unconditionally now that
		// short input (where that top result was often wrong) is filtered out above.
		TextObject textObject = textObjectFactory.forText(text);
		List<DetectedLanguage> probabilities = languageDetector.getProbabilities(textObject);
		System.err.println("[Offline Translate] getProbabilities(\"" + text + "\") -> " + probabilities);
		if (probabilities.isEmpty())
		{
			return null;
		}

		return Language.fromCode(probabilities.get(0).getLocale().getLanguage());
	}
}
