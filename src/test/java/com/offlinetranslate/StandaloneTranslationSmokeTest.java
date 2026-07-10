package com.offlinetranslate;

import com.offlinetranslate.model.ModelManager;
import com.offlinetranslate.model.PackDirection;
import com.offlinetranslate.translate.TranslationEngine;

/**
 * Downloads a real language pack and runs the translation engine directly, with no RuneLite
 * client involved - a standalone way to validate the ONNX tensor wiring and generation loop
 * without needing a running game session. Not a unit test (network + a real model download),
 * just a manual smoke test: {@code ./gradlew smokeTest}.
 */
public class StandaloneTranslationSmokeTest
{
	public static void main(String[] args) throws Exception
	{
		ModelManager modelManager = new ModelManager();
		TranslationEngine engine = new TranslationEngine(modelManager);

		try
		{
			download(modelManager, Language.SPANISH, PackDirection.TO_ENGLISH);

			String[] spanish = {
				"hola, como estas",
				"necesito ayuda con el jefe",
				"donde esta el banco mas cercano"
			};
			System.out.println("\n=== Spanish -> English ===");
			for (String phrase : spanish)
			{
				long start = System.currentTimeMillis();
				String translated = engine.translateToEnglish(phrase, Language.SPANISH);
				System.out.printf("  \"%s\" -> \"%s\" (%dms)%n", phrase, translated, System.currentTimeMillis() - start);
			}

			download(modelManager, Language.SPANISH, PackDirection.FROM_ENGLISH);

			String[] english = {
				"hello, how are you",
				"I need help with the boss",
				"where is the nearest bank"
			};
			System.out.println("\n=== English -> Spanish ===");
			for (String phrase : english)
			{
				long start = System.currentTimeMillis();
				String translated = engine.translateFromEnglish(phrase, Language.SPANISH);
				System.out.printf("  \"%s\" -> \"%s\" (%dms)%n", phrase, translated, System.currentTimeMillis() - start);
			}

			System.out.println("\nSMOKE TEST PASSED");
		}
		catch (Throwable t)
		{
			System.out.println("\nSMOKE TEST FAILED");
			t.printStackTrace();
			System.exit(1);
		}
		finally
		{
			engine.shutdown();
			modelManager.shutdown();
		}
		System.exit(0);
	}

	private static void download(ModelManager modelManager, Language language, PackDirection direction) throws Exception
	{
		System.out.println("Downloading " + language.getDisplayName() + " " + direction + "...");
		modelManager.download(language, direction, progress ->
			System.out.printf("\r  %s: %.0f%%", progress.getCurrentFile(), progress.overallFraction() * 100))
			.join();
		System.out.println();
	}
}
