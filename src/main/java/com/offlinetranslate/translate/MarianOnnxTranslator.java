package com.offlinetranslate.translate;

import ai.djl.sentencepiece.SpTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a single OPUS-MT language-pair ONNX pack (one direction: either {@code lang->en} or
 * {@code en->lang}) via raw ONNX Runtime calls: SentencePiece-encode the input, run the
 * encoder once, then greedily decode token-by-token with a growing KV cache until an
 * end-of-sequence token or a length cap is hit.
 * <p>
 * <b>Status: implemented against the real, introspected tensor names/shapes of Xenova's
 * OPUS-MT ONNX exports (verified via {@code onnx.load} on an actual downloaded model), but not
 * yet exercised end-to-end inside a running RuneLite client</b> - this environment has no OSRS
 * client to drive real chat through. Decoding is greedy (not beam search), which trades some
 * translation quality for a much simpler, easier-to-verify implementation; that's a deliberate
 * v1 choice, not an oversight. Treat the first real run against a downloaded pack as the actual
 * test of this class, and expect to need to debug tensor shape/name mismatches against
 * whichever exact pack you try first.
 */
@Slf4j
class MarianOnnxTranslator implements AutoCloseable
{
	private static final int MAX_INPUT_TOKENS = 128;
	private static final int MAX_NEW_TOKENS = 96;

	private final OrtEnvironment env;
	private final OrtSession encoderSession;
	private final OrtSession decoderSession;
	private final SpTokenizer sourceTokenizer;
	private final SpTokenizer targetTokenizer;
	private final MarianConfig config;

	MarianOnnxTranslator(File packDir) throws TranslationException
	{
		try
		{
			this.env = OrtEnvironment.getEnvironment();
			OrtSession.SessionOptions options = new OrtSession.SessionOptions();
			this.encoderSession = env.createSession(new File(packDir, "onnx/encoder_model_quantized.onnx").getAbsolutePath(), options);
			this.decoderSession = env.createSession(new File(packDir, "onnx/decoder_model_merged_quantized.onnx").getAbsolutePath(), options);
			this.sourceTokenizer = new SpTokenizer(new File(packDir, "source.spm").toPath());
			this.targetTokenizer = new SpTokenizer(new File(packDir, "target.spm").toPath());
			this.config = MarianConfig.load(new File(packDir, "config.json"));
		}
		catch (OrtException | IOException e)
		{
			throw new TranslationException("Failed to load language pack from " + packDir, e);
		}
	}

	synchronized String translate(String text) throws TranslationException
	{
		try
		{
			int[] sourceIds = sourceTokenizer.getProcessor().encode(text);
			if (sourceIds.length > MAX_INPUT_TOKENS - 1)
			{
				sourceIds = java.util.Arrays.copyOf(sourceIds, MAX_INPUT_TOKENS - 1);
			}
			long[] inputIds = new long[sourceIds.length + 1];
			for (int i = 0; i < sourceIds.length; i++)
			{
				inputIds[i] = sourceIds[i];
			}
			inputIds[sourceIds.length] = config.eosTokenId;
			int srcLen = inputIds.length;

			long[] attentionMask = new long[srcLen];
			java.util.Arrays.fill(attentionMask, 1L);

			float[][][] encoderHiddenStates = runEncoder(inputIds, attentionMask, srcLen);

			return runDecoder(encoderHiddenStates, attentionMask, srcLen);
		}
		catch (OrtException e)
		{
			throw new TranslationException("Translation failed", e);
		}
	}

	private float[][][] runEncoder(long[] inputIds, long[] attentionMask, int srcLen) throws OrtException
	{
		Map<String, OnnxTensor> inputs = new HashMap<>();
		try
		{
			inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][]{inputIds}));
			inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][]{attentionMask}));
			try (OrtSession.Result result = encoderSession.run(inputs))
			{
				Object value = result.get("last_hidden_state").get().getValue();
				return (float[][][]) value;
			}
		}
		finally
		{
			inputs.values().forEach(OnnxTensor::close);
		}
	}

	@SuppressWarnings("unchecked")
	private String runDecoder(float[][][] encoderHiddenStates, long[] encoderAttentionMask, int srcLen) throws OrtException
	{
		int layers = config.decoderLayers;
		int heads = config.attentionHeads;
		int headDim = config.headDim();

		// past_key_values for step 0: zero-length sequence dimension (no cache yet).
		float[][][][] emptyDecoderCache = new float[1][heads][0][headDim];
		float[][][][] emptyEncoderCache = new float[1][heads][0][headDim];
		float[][][][][] decoderKeyCache = new float[layers][][][][];
		float[][][][][] decoderValueCache = new float[layers][][][][];
		float[][][][][] encoderKeyCache = new float[layers][][][][];
		float[][][][][] encoderValueCache = new float[layers][][][][];
		for (int i = 0; i < layers; i++)
		{
			decoderKeyCache[i] = emptyDecoderCache;
			decoderValueCache[i] = emptyDecoderCache;
			encoderKeyCache[i] = emptyEncoderCache;
			encoderValueCache[i] = emptyEncoderCache;
		}

		StringBuilder outputPieces = new StringBuilder();
		int[] generatedIds = new int[MAX_NEW_TOKENS];
		int generatedCount = 0;
		long nextInputToken = config.decoderStartTokenId;
		boolean useCache = false;

		for (int step = 0; step < MAX_NEW_TOKENS; step++)
		{
			Map<String, OnnxTensor> inputs = new HashMap<>();
			try
			{
				inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][]{{nextInputToken}}));
				inputs.put("encoder_attention_mask", OnnxTensor.createTensor(env, new long[][]{encoderAttentionMask}));
				inputs.put("encoder_hidden_states", OnnxTensor.createTensor(env, new float[][][]{encoderHiddenStates[0]}));
				inputs.put("use_cache_branch", OnnxTensor.createTensor(env, new boolean[]{useCache}));
				for (int layer = 0; layer < layers; layer++)
				{
					inputs.put("past_key_values." + layer + ".decoder.key", OnnxTensor.createTensor(env, decoderKeyCache[layer]));
					inputs.put("past_key_values." + layer + ".decoder.value", OnnxTensor.createTensor(env, decoderValueCache[layer]));
					inputs.put("past_key_values." + layer + ".encoder.key", OnnxTensor.createTensor(env, encoderKeyCache[layer]));
					inputs.put("past_key_values." + layer + ".encoder.value", OnnxTensor.createTensor(env, encoderValueCache[layer]));
				}

				try (OrtSession.Result result = decoderSession.run(inputs))
				{
					float[][][] logits = (float[][][]) result.get("logits").get().getValue();
					long nextId = argmaxExcluding(logits[0][0], config.padTokenId);

					for (int layer = 0; layer < layers; layer++)
					{
						decoderKeyCache[layer] = (float[][][][]) result.get("present." + layer + ".decoder.key").get().getValue();
						decoderValueCache[layer] = (float[][][][]) result.get("present." + layer + ".decoder.value").get().getValue();
						encoderKeyCache[layer] = (float[][][][]) result.get("present." + layer + ".encoder.key").get().getValue();
						encoderValueCache[layer] = (float[][][][]) result.get("present." + layer + ".encoder.value").get().getValue();
					}

					if (nextId == config.eosTokenId)
					{
						break;
					}

					generatedIds[generatedCount++] = (int) nextId;
					nextInputToken = nextId;
					useCache = true;
				}
			}
			finally
			{
				inputs.values().forEach(OnnxTensor::close);
			}
		}

		int[] finalIds = java.util.Arrays.copyOf(generatedIds, generatedCount);
		return targetTokenizer.getProcessor().decode(finalIds);
	}

	private static long argmaxExcluding(float[] logits, int excludedId)
	{
		int best = -1;
		float bestVal = -Float.MAX_VALUE;
		for (int i = 0; i < logits.length; i++)
		{
			if (i == excludedId)
			{
				continue;
			}
			if (logits[i] > bestVal)
			{
				bestVal = logits[i];
				best = i;
			}
		}
		return best;
	}

	@Override
	public void close()
	{
		try
		{
			encoderSession.close();
			decoderSession.close();
			sourceTokenizer.close();
			targetTokenizer.close();
		}
		catch (OrtException e)
		{
			log.warn("Error closing ONNX sessions", e);
		}
	}
}
