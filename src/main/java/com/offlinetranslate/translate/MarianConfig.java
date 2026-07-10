package com.offlinetranslate.translate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * The handful of fields we need out of a MarianMT {@code config.json}. Values vary per
 * language pack (vocab size especially), so this is read fresh per pack rather than assumed.
 */
class MarianConfig
{
	final int decoderStartTokenId;
	final int eosTokenId;
	final int padTokenId;
	final int decoderLayers;
	final int attentionHeads;
	final int dModel;

	private MarianConfig(int decoderStartTokenId, int eosTokenId, int padTokenId, int decoderLayers, int attentionHeads, int dModel)
	{
		this.decoderStartTokenId = decoderStartTokenId;
		this.eosTokenId = eosTokenId;
		this.padTokenId = padTokenId;
		this.decoderLayers = decoderLayers;
		this.attentionHeads = attentionHeads;
		this.dModel = dModel;
	}

	int headDim()
	{
		return dModel / attentionHeads;
	}

	static MarianConfig load(File configJson) throws IOException
	{
		try (FileReader reader = new FileReader(configJson))
		{
			JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
			return new MarianConfig(
				obj.get("decoder_start_token_id").getAsInt(),
				obj.get("eos_token_id").getAsInt(),
				obj.get("pad_token_id").getAsInt(),
				obj.get("decoder_layers").getAsInt(),
				obj.get("decoder_attention_heads").getAsInt(),
				obj.get("d_model").getAsInt()
			);
		}
	}
}
