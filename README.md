# Offline Translate

A RuneLite plugin that translates OSRS chat using language models downloaded once and run
**entirely on your machine** afterward. Unlike [RuneLingual](https://github.com/IaKee/RuneLingual-Plugin),
this never sends chat text to a third-party translation API (DeepL, etc.) - the one-time
download is fetching model weights, not translating your (or anyone else's) messages.

## What it does

- **Auto-detects** the language of incoming chat and flags the sender's name with a small
  language badge when it differs from your preferred language.
- **Logs translations** of detected foreign messages to the side panel (sender, original text,
  translated text).
- **`/t <message>`** translates what you type from your preferred language into the current
  output language before it's sent, so the other player sees it in their language. The output
  language auto-updates to match whoever last spoke a different language to you (configurable).
- **Downloadable language packs**, managed from the side panel, with a prompt to download a
  pack when auto-detect hits a language you don't have yet.

## Status - read this before relying on anything here

**Translation itself is verified working, for real, with a real downloaded model** - not just
"should work in theory." There's no OSRS client in the environment this was built in, but the
translation engine needs no game client at all, so it was run standalone
(`./gradlew smokeTest`): downloaded the real `Xenova/opus-mt-es-en` and `opus-mt-en-es` packs
from Hugging Face and translated real sentences end to end. Current output:

```
=== Spanish -> English ===
  "hola, como estas"                -> "Hello, how are you?"
  "necesito ayuda con el jefe"      -> "I need help with the boss."
  "donde esta el banco mas cercano" -> "where the nearest bank is"

=== English -> Spanish ===
  "hello, how are you"          -> "Hola, ¿cómo estás?"
  "I need help with the boss"   -> "Necesito ayuda con el jefe."
  "where is the nearest bank"   -> "donde está el banco más cercano"
```

That run caught and fixed three real bugs that a code-reading pass alone wouldn't have found -
worth knowing about since they show what "verified" actually means here, and because similar
issues are the most likely thing to show up if you add a new language or swap model variants:

1. **Zero-length KV-cache tensors** - ONNX Runtime's Java array-based tensor factory
   (`OnnxTensor.createTensor(env, Object)`) rejects any array with a zero-length dimension,
   even though ONNX itself supports zero-size tensors fine. Fixed by building those tensors
   through the explicit-shape `FloatBuffer` overload instead, which has no such restriction.
2. **The merged decoder graph doesn't refresh `present.*.encoder.key/value` on cached steps** -
   it returns an empty placeholder there once `use_cache_branch=true`, rather than recomputing
   or passing through the real cross-attention cache. The encoder K/V captured from the first
   (uncached) step now gets threaded forward unchanged on every later step instead of being
   overwritten from each step's output.
3. **The real one:** `separate_vocabs: false` in `tokenizer_config.json` does *not* mean
   `source.spm`/`target.spm` share SentencePiece's own internal id numbering - it means both
   sides share a single `vocab.json`. Feeding the model each `.spm` file's own internal
   `encode()`/`decode()` ids (which is what `SpProcessor.encode()`/`decode()` naively give you)
   silently produces fluent-looking but completely wrong translations, because those ids point
   at different vocabulary entries than the ones the ONNX embedding/output layers were trained
   on. (Confirmed concretely: source.spm's own id for the piece `"▁hola"` is `10120`; the same
   piece's real id in `vocab.json` - what the model actually expects - is `22088`.) Fixed by
   using the `.spm` files only for piece *segmentation* and detokenization spacing, and
   `vocab.json` for the actual id lookups in both directions. See `MarianVocab`.

**Not yet verified - this environment still has no OSRS client to test real chat through:**
- The `/t` outgoing-translation mechanism (`OutgoingTranslateKeyListener`). It works by
  rewriting `VarClientStr.CHATBOX_TYPED_TEXT` when Enter is pressed, on the assumption that
  RuneLite's key-listener chain runs before the game's own chat-send handling reads that same
  variable. That's how other plugins that alter outgoing text are believed to work, but it
  hasn't been confirmed live here. **This is also the one part of this plugin that's closer to
  "modifying game communication" than straightforward client-side rendering** - test it
  carefully.
- Flag-icon rendering in real chat (`ChatIconManager` wiring) and the side panel's live
  behavior - straightforward, well-established RuneLite patterns, but unexercised against a
  real client/session in this environment.
- Translation quality on further languages/sentences - greedy decoding (not beam search) was a
  deliberate v1 simplicity tradeoff, so expect occasional rougher phrasing on longer or more
  ambiguous input than the short chat-style lines tested above.
- The right-click "Translate" option on individual chat messages from the original design
  wasn't implemented. Reliably detecting "this menu is a chat-message right-click" (vs. any
  other right-click context) needs real client testing to get the widget/menu-action matching
  right, and shipping a guessed version risked a "Translate" option showing up in the wrong
  place. The side-panel log covers the same need (translated incoming messages without
  clicking anything) and was the safer thing to ship first.

**First things to do locally:** `./gradlew runOfflineTranslate`, log in, download a pack from
the side panel, and try `/t` with a friend or alt account - that exercises the two pieces that
couldn't be tested without a real client.

## Language pack coverage

Every pack is English-paired (translating between two non-English languages pivots through
English). Not every language has a model in both directions - this reflects real gaps in the
upstream [Helsinki-NLP OPUS-MT](https://huggingface.co/Helsinki-NLP) models, not an
implementation limitation:

| Language | Incoming (→ English) | Outgoing (English →) |
|---|---|---|
| Spanish, French, German, Italian, Dutch, Russian, Chinese, Arabic, Swedish, Finnish, Danish, Czech, Hindi, Vietnamese, Ukrainian, Indonesian, Hungarian, Afrikaans | ✅ | ✅ |
| Japanese | ✅ | ✅ (different upstream model family than the incoming direction) |
| Korean, Polish | ✅ | ❌ (no English→ model published upstream) |

Portuguese and Turkish are not included in v1 - Portuguese has no Xenova ONNX conversion
published at all, and the only available Turkish model is a differently-structured "big"
variant that wasn't worth the added risk of assuming it matches the standard pipeline here.
Both are reasonable additions once someone's tested the standard pipeline live.

## Architecture

```
Language.java              - the language catalog + which HF model repos back each direction
model/ModelManager          - downloads & caches ONNX packs into ~/.runelite/offline-translate
translate/ChatLanguageDetector - offline n-gram language ID (no model download needed)
translate/MarianOnnxTranslator - runs one pack: SentencePiece encode -> ONNX encoder ->
                                  greedy KV-cached ONNX decoder loop -> SentencePiece decode
translate/TranslationEngine - loads/caches translators per (language, direction), pivots
                               through English for non-English-to-non-English translation
chat/ChatTranslationService  - ChatMessage hook: detect, flag sender, log translation
chat/OutgoingTranslateKeyListener - /t command: rewrite typed text before send (experimental)
ui/OfflineTranslatePanel    - side panel: language selectors, pack manager, translated log
```

## Building

Standard RuneLite external plugin, structured like
[runelite/example-plugin](https://github.com/runelite/example-plugin):

```
./gradlew build
```

## Trying it out locally

```
./gradlew runOfflineTranslate
```

This launches the real RuneLite client with the plugin registered (via
`ExternalPluginManager.loadBuiltin`) - no need to check out RuneLite's own source tree. Log in
with your own account, enable "Offline Translate" in the plugin list, and open its side panel.

### Testing translation without a game client

```
./gradlew smokeTest
```

Downloads a real language pack and runs the translation engine directly - useful for checking
the ONNX/tokenizer pipeline in isolation (e.g. after adding a new language) without needing to
log into the game at all. See [Status](#status---read-this-before-relying-on-anything-here)
for sample output.
