package com.offlinetranslate.model;

/**
 * Which translation direction a downloaded language pack covers. A pack is always
 * English-paired: either translating {@code Language -> English} (used to translate incoming
 * chat into your preferred language) or {@code English -> Language} (used for the outgoing
 * {@code /t} command). Not every language has both - see {@link com.offlinetranslate.Language}.
 */
public enum PackDirection
{
	TO_ENGLISH,
	FROM_ENGLISH
}
