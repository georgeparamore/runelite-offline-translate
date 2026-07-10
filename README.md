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

This was built in one pass without access to a running OSRS client to test against, so it's
being published honest about what's verified vs. not:

**Solid / verified:**
- Project scaffold, config, and side panel UI - compiles clean, standard RuneLite patterns.
- Language pack download/cache manager - downloads real, confirmed-to-exist model files from
  Hugging Face (see [Language pack coverage](#language-pack-coverage)).
- Language detection (`ChatLanguageDetector`) - uses the well-established
  [language-detector](https://github.com/optimaize/language-detector) library, no ML model
  needed, works immediately. Accuracy on short chat lines (a few words) will be noticeably
  worse than on full sentences - that's the nature of statistical language ID on short text,
  not a bug.
- Translation engine tensor wiring (`MarianOnnxTranslator`) - the encoder/decoder input and
  output tensor names, shapes, and the KV-cache generation loop were written against the
  **actual introspected ONNX graph** of a downloaded model (`onnx.load` on
  `Xenova/opus-mt-es-en`), not guessed from documentation. Config values (vocab size, special
  token ids, layer/head counts) are read from each pack's own `config.json` rather than
  hardcoded.

**Not yet verified end-to-end - this environment has no OSRS client to test real chat through:**
- The actual translation *quality* and whether the generation loop runs correctly against a
  live pack. Decoding is greedy (not beam search) - a deliberate simplicity tradeoff for a v1,
  not an oversight, but it will produce lower-quality translations than production translation
  tools that use beam search.
- The `/t` outgoing-translation mechanism (`OutgoingTranslateKeyListener`). It works by
  rewriting `VarClientStr.CHATBOX_TYPED_TEXT` when Enter is pressed, on the assumption that
  RuneLite's key-listener chain runs before the game's own chat-send handling reads that same
  variable. That's how other plugins that alter outgoing text are believed to work, but it
  hasn't been confirmed live here. **This is also the one part of this plugin that's closer to
  "modifying game communication" than straightforward client-side rendering** - test it
  carefully, and see the note below.
- The right-click "Translate" option on individual chat messages from the original design
  wasn't implemented. Reliably detecting "this menu is a chat-message right-click" (vs. any
  other right-click context) needs real client testing to get the widget/menu-action matching
  right, and shipping a guessed version risked a "Translate" option showing up in the wrong
  place. The side-panel log covers the same need (see translated incoming messages without
  clicking anything) and was the safer thing to ship first.

**First things to do locally:** download one language pack, try `/t` to a friend or alt
account, and watch the console log for stack traces from `MarianOnnxTranslator` if translation
silently fails - the tensor wiring is the part most likely to need a fix against a real run.

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
