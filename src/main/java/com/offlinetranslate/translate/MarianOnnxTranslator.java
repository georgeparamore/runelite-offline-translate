package com.offlinetranslate.translate;

import ai.djl.sentencepiece.SpTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
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
	private final MarianVocab vocab;

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
			this.vocab = MarianVocab.load(new File(packDir, "vocab.json"));
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
			// The .spm files only do piece *segmentation* here - SpProcessor.encode() would
			// return each file's own internal SentencePiece piece-ids, which are a different
			// numbering than the model's actual vocabulary (confirmed empirically: e.g. for
			// opus-mt-es-en, source.spm's own id for the piece "▁hola" is 10120, but
			// vocab.json - the id space the ONNX embedding/output layers actually use - has it
			// at 22088). The real ids have to come from vocab.json.
			String[] pieces = sourceTokenizer.getProcessor().tokenize(text);
			int tokenCount = Math.min(pieces.length, MAX_INPUT_TOKENS - 1);
			long[] inputIds = new long[tokenCount + 1];
			for (int i = 0; i < tokenCount; i++)
			{
				inputIds[i] = vocab.idFor(pieces[i]);
			}
			inputIds[tokenCount] = config.eosTokenId;
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

		// past_key_values for step 0: zero-length sequence dimension (no cache yet). Note this
		// can't be built via OnnxTensor.createTensor(env, Object) - its Java-array shape
		// inference rejects any zero-length dimension ("Supplied array has a zero dimension"),
		// even though ONNX Runtime itself supports zero-size tensors fine. tensor4f() below
		// goes through the explicit-shape FloatBuffer overload instead, which has no such check.
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
					inputs.put("past_key_values." + layer + ".decoder.key", tensor4f(decoderKeyCache[layer], heads, headDim));
					inputs.put("past_key_values." + layer + ".decoder.value", tensor4f(decoderValueCache[layer], heads, headDim));
					inputs.put("past_key_values." + layer + ".encoder.key", tensor4f(encoderKeyCache[layer], heads, headDim));
					inputs.put("past_key_values." + layer + ".encoder.value", tensor4f(encoderValueCache[layer], heads, headDim));
				}

				try (OrtSession.Result result = decoderSession.run(inputs))
				{
					float[][][] logits = (float[][][]) result.get("logits").get().getValue();
					long nextId = argmaxExcluding(logits[0][0], config.padTokenId);

					for (int layer = 0; layer < layers; layer++)
					{
						decoderKeyCache[layer] = (float[][][][]) result.get("present." + layer + ".decoder.key").get().getValue();
						decoderValueCache[layer] = (float[][][][]) result.get("present." + layer + ".decoder.value").get().getValue();
						// present.N.encoder.* is only meaningfully populated on the first
						// (use_cache_branch=false) call - the cached branch returns an empty
						// placeholder tensor there rather than recomputing or passing through
						// the real cross-attention cache (confirmed empirically: it comes back
						// with a zero-length batch dimension on cached steps). So the encoder
						// K/V captured from step 0 has to be threaded forward unchanged on every
						// later step instead of being refreshed from each step's output.
						if (!useCache)
						{
							encoderKeyCache[layer] = (float[][][][]) result.get("present." + layer + ".encoder.key").get().getValue();
							encoderValueCache[layer] = (float[][][][]) result.get("present." + layer + ".encoder.value").get().getValue();
						}
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

		// Same vocab.json indirection as encoding, in reverse: map generated vocab ids back to
		// piece strings, then let target.spm's own detokenizer handle "▁"-marker spacing/
		// punctuation-attachment rules - buildSentence() takes piece strings, not ids, so it's
		// unaffected by the id-space mismatch that broke naive decode(ids).
		String[] outputPieces = new String[generatedCount];
		for (int i = 0; i < generatedCount; i++)
		{
			outputPieces[i] = vocab.pieceFor(generatedIds[i]);
		}
		return targetTokenizer.getProcessor().buildSentence(outputPieces);
	}

	/** Builds a [1, heads, seqLen, headDim] float tensor via the explicit-shape buffer API, which (unlike the Object-array factory) tolerates seqLen == 0. */
	private OnnxTensor tensor4f(float[][][][] data, int heads, int headDim) throws OrtException
	{
		int seqLen = data[0][0].length;
		long[] shape = {1, heads, seqLen, headDim};
		FloatBuffer buffer = FloatBuffer.allocate(heads * seqLen * headDim);
		for (float[][] head : data[0])
		{
			for (float[] position : head)
			{
				buffer.put(position);
			}
		}
		buffer.rewind();
		return OnnxTensor.createTensor(env, buffer, shape);
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
