package com.offlinetranslate.translate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * The piece-string &lt;-&gt; numeric-id mapping the ONNX model's embedding/output layers
 * actually use ({@code vocab.json}). This is a completely different id space from whatever
 * {@code source.spm}/{@code target.spm}'s own {@code SpProcessor.encode()/decode()} produce
 * internally - those raw SentencePiece ids are specific to each .spm file's own piece
 * ordering, not the model's vocabulary. (Confirmed empirically: for opus-mt-es-en, the raw
 * source.spm id for "&#9601;hola" and vocab.json's id for the same piece are different
 * numbers - 10120 vs 22088.) With {@code separate_vocabs: false} in tokenizer_config.json,
 * there's one shared vocab.json for both directions, used here for the id lookup while the
 * .spm files are only used for piece *segmentation* and detokenization spacing.
 */
class MarianVocab
{
	private final Map<String, Integer> pieceToId;
	private final Map<Integer, String> idToPiece;
	private final int unknownId;

	private MarianVocab(Map<String, Integer> pieceToId)
	{
		this.pieceToId = pieceToId;
		this.unknownId = pieceToId.getOrDefault("<unk>", 0);
		this.idToPiece = new HashMap<>();
		for (Entry<String, Integer> entry : pieceToId.entrySet())
		{
			idToPiece.put(entry.getValue(), entry.getKey());
		}
	}

	int idFor(String piece)
	{
		Integer id = pieceToId.get(piece);
		return id != null ? id : unknownId;
	}

	String pieceFor(int id)
	{
		String piece = idToPiece.get(id);
		return piece != null ? piece : "";
	}

	static MarianVocab load(File vocabJson) throws IOException
	{
		try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(vocabJson.toPath()), StandardCharsets.UTF_8))
		{
			JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
			Map<String, Integer> map = new HashMap<>();
			for (Entry<String, com.google.gson.JsonElement> entry : obj.entrySet())
			{
				map.put(entry.getKey(), entry.getValue().getAsInt());
			}
			return new MarianVocab(map);
		}
	}
}
