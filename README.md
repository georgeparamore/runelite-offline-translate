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
- **Translate hotkey (default Ctrl+T)** - type your message normally, press the hotkey to
  translate it in place from your preferred language into the current output language, then
  send it yourself with a completely ordinary Enter press. The output language auto-updates to
  match whoever last spoke a different language to you.
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

**Fixed after real in-client testing (thanks to a live test run on an actual Intel Mac):**
4. **DJL's SentencePiece module dropped Intel Mac (`osx-x86_64`) native binaries starting in
   version 0.30.0** - it only bundles `osx-aarch64` (Apple Silicon), `linux-x86_64`/`aarch64`,
   and `win-x86_64` from then on. On Intel Mac this failed at pack-load time with `Resource not
   found in classpath: native/lib/osx-x86_64/libsentencepiece_native.dylib` - a missing binary,
   not a code bug. Pinned to `0.29.0`, the last release that still ships it (its
   `SpTokenizer`/`SpProcessor` API is byte-for-byte identical to the current one, confirmed by
   diffing the source).
5. **The default `/t` command prefix collided with OSRS's own client behavior** - the game
   itself reserves a leading `/` in the chatbox to mean "switch to a private message with this
   player name," so typing `/t hello` got swallowed by that (showing "Unknown command: /t" or
   freezing chat input) before this plugin's key listener could ever act on it. Changed the
   default to `!t ` and added a runtime check that warns (once) if a prefix starting with `/`
   is configured, instead of silently doing nothing.
6. **The `/t`/`!t` prefix match was case-sensitive**, but OSRS's chatbox auto-capitalizes the
   first letter you type - `!t hello` actually arrives in `CHATBOX_TYPED_TEXT` as `!T hello` by
   the time Enter is pressed, so it never matched and the raw auto-capitalized text got sent
   untranslated every time. Match is now case-insensitive.
7. **The real one: translating cold (first use) inside the Enter keypress handler corrupted
   chat sending entirely**, not just failed slowly. First-time loading (ONNX sessions + the
   native SentencePiece library) takes 1-4 seconds; running that synchronously inside the
   keypress handler blocked the UI thread for that long, and confirmed live, that didn't just
   feel slow - it broke the chatbox's send state, cycling between the "Press Enter to Chat"
   placeholder and the typed text with the message never actually sending, for *any* message
   while it was stuck (not just the `!t` one). Fixed by never calling the blocking translate()
   from the keypress handler unless the translator is already warmed up in memory
   (`TranslationEngine.isWarm`/`warmUp`) - a cold `!t` now sends untranslated immediately and
   warms up in the background for next time, instead of blocking. Also proactively warms up on
   plugin startup, right after a pack finishes downloading, and when you change your
   preferred/output language in the panel, so the cold case is rare in practice.

8. **Redesigned outgoing translation entirely, after the `/t`/`!t`-on-Enter approach was
   confirmed to corrupt chat sending three fixes in a row.** The core assumption behind it -
   that rewriting `VarClientStr.CHATBOX_TYPED_TEXT` inside the *same* keypress that triggers
   the game's own send logic would just substitute the text - turned out to be wrong, not
   mistimed: it broke the chatbox's send state outright (Enter cycling between the placeholder
   and the typed text, nothing ever sending, for any message while stuck). The fix came from
   checking how [RuneLingual](https://github.com/IaKee/RuneLingual-Plugin) - a plugin that has
   real, working live translation - actually does it: it never touches outgoing packets at
   all, only rewrites displayed text locally after a message already exists as a `MessageNode`
   (the same safe pattern this plugin already used for *incoming* translation). Other RuneLite
   plugins that do rewrite `CHATBOX_TYPED_TEXT` successfully (live swear filters,
   auto-capitalization) all do it progressively as you type, never during the send keystroke
   itself. So outgoing translation here is now **a dedicated hotkey (default Ctrl+T) that
   translates the chatbox text in place, completely decoupled from Enter** - you translate,
   look at the result, then send it yourself with an entirely untouched, normal Enter press.
   This still changes what's actually transmitted (unlike RuneLingual's local-only approach),
   it just no longer shares a keystroke with the send action.

9. **Incoming detection never fired on realistic OSRS chat messages, for two compounding
   reasons**, both confirmed live and fixed in `ChatLanguageDetector`:
   - The detector's own `detect()` method is deliberately over-strict (its javadoc says as
     much) and returned nothing for plain, unambiguous input like `"Hola"`. Switched to
     `getProbabilities()` and take the top result, per the library's own recommendation.
   - That alone wasn't enough: loading all 70 of the library's built-in language profiles
     meant short chat-length text could spuriously match an unrelated, irrelevant language's
     n-gram profile. Confirmed with a real battery of test phrases: `"good luck"` -> Polish at
     92%, `"well done"` -> Dutch at 85%, `"hello"` -> Italian at 60% - all wrong, all
     *confidently* wrong, not hedged. The same phrases extended to real-sentence length
     (23+ characters) hit 99.99%+ correct in every case tried. So detection now (a) only
     loads profiles for the ~13 languages this plugin actually supports, cutting out
     irrelevant noise like Breton/Somali/Turkish competing for short-text matches, and
     (b) requires a minimum length before attempting detection at all, rather than trying to
     salvage short input with a confidence threshold - the wrong answers above were often
     *more* "confident" than correct ones elsewhere, so confidence alone can't tell real
     detections from spurious ones here. Net effect: short greetings/single words won't get
     flagged (an accepted tradeoff), but full sentences are genuinely reliable.

10. **In-chat flag badges were a solid-color box with a 2-letter code, not a flag** - the
    original design deliberately avoided drawing flag emoji glyphs (Java's `Graphics2D`
    doesn't reliably compose multi-codepoint emoji sequences into the right glyph even on
    platforms with a color-emoji font), but confirmed live that just read as an arbitrary
    colored box. Replaced with real per-country flag patterns - horizontal/vertical color
    bands, simplified Nordic crosses, Czech's wedge, Vietnam's star simplified to a dot - drawn
    directly via `Graphics2D` shapes. Same zero-font-dependency safety, now actually
    recognizable as flags. See `flagRenderTest` to preview all of them without a client.
11. **Right-click "Translate" is now implemented**, via `MenuManager.addPlayerMenuItem()` - the
    same mechanism "Add friend"/"Report" use, so it works on both world player right-clicks and
    chat name right-clicks without needing to reverse-engineer widget/menu-action matching for
    "this specifically is a chat right-click" (the earlier, riskier approach this was
    originally deferred over). Translates whichever player's most recent tracked message into
    your preferred language and logs it to the side panel.
12. **Translating the entire typed line, including OSRS's own chat-channel-switching prefixes,
    silently broke which channel the message actually went to.** Typing `/hello` (friends
    chat) or `//hello` (clan chat) and using the translate hotkey fed the *whole* string,
    prefix included, into the translation model - garbling or losing the routing prefix
    entirely, confirmed live: a clan/FC message came out prefixed with `#` and untranslated
    instead of routing and translating correctly. `OutgoingTranslateKeyListener` now detects
    and strips these prefixes first (`/`, `//`, `///`, `////`, `/c `, `/g `, `/gc `, `/@c`,
    `/@g`, `/@gc`, `/@f`, `/@p` - the same set RuneLingual's `PlayerMessage.java` checks for
    the same reason), translates only the message body, and reassembles prefix + translation
    before writing back.
13. **First (incorrect) attempt at the "Translated messages" log bug** - diagnostics confirmed
    translate-and-log was succeeding on every incoming message, but nothing ever appeared.
    Initially assumed to be a scroll-reachability problem and "fixed" by wrapping the whole
    panel in a manual outer `JScrollPane`. That did not fix it (confirmed live) - see item 14
    for the actual cause, discovered afterward by reading RuneLite's own `PluginPanel` source.
14. **The real cause: `OfflineTranslatePanel` was missing `@Singleton`.** Without it, Guice
    handed out a *separate instance* to every injection point - one for
    `OfflineTranslatePlugin.panel` (the instance actually wrapped into the `NavigationButton`
    and shown in the sidebar), and a different one for `ChatTranslationService.panel` (the
    instance `logTranslatedMessage()`/`setDetectedOutputLanguage()` were actually called on).
    Every translation was logging correctly to an invisible clone panel that was never attached
    to anything on screen - not a layout bug at all. Also confirmed by reading RuneLite's
    `PluginPanel.java` that it already wraps every plugin panel in its own outer `JScrollPane`
    before adding it to the sidebar (`ClientUI.addNavigation` adds `getWrappedPanel()`, not the
    panel itself), so the manual outer scrollpane from item 13 was redundant and has been
    removed - back to a single `BoxLayout` column, same pattern already proven to work for the
    pack list. **Confirmed live** - both auto-detected and manually-translated messages now show
    up in the side panel.
15. **Right-click "Translate" moved from the player-option system to the chatbox itself**, per
    request - the old `MenuManager.addPlayerMenuItem()` mechanism only ever appeared on the 3D
    player model or a chat *name* click specifically. First attempt used `MenuEntryAdded` on
    `WidgetInfo.CHATBOX_MESSAGE_LINES`, piggybacking on whatever default option (e.g. "Report")
    the game already added for a hovered line - **confirmed live to never show up at all**, with
    no diagnostics on its early-return paths to say why. Rewritten again on `MenuOpened` instead,
    which fires once per right-click regardless of how many entries exist: this checks the mouse
    canvas position directly against every chat line widget's bounds and adds "Translate"
    unconditionally when the cursor is over one, rather than depending on OSRS having offered
    something there first. The hovered widget is matched back to a live `MessageNode` by
    comparing rendered text content (tag-stripped) against `client.getChatLineMap()`, since OSRS
    renders each chat line as one text widget rather than separate name/message widgets. **Not
    yet exercised live** - this is still the least-tested piece in the codebase.
16. **Side panel log entries were showing raw formatting junk** - literal text like
    `<img=10>Optimism` instead of a clean name, and a tofu box instead of a flag, because a
    plain Swing `JLabel` can't render OSRS's `<img=N>`/`<col=...>` chat tags or flag emoji
    glyphs any better than the in-game chat font could (see item 10). Sender names are now
    tag-stripped before display, and flags reuse `FlagIconFactory`'s real color-shape rendering
    (now public, with a size parameter) instead of an emoji glyph. Entries also got a
    card-style redesign and a "Clear" button.
17. **`MarianOnnxTranslator`'s length caps raised from 128/96 to 400/400 tokens** - the decode
    loop already breaks early on the model's own end-of-sequence token, so this costs nothing
    for the common short-chat-line case; it only matters for unusually long messages that were
    silently coming back cut off mid-sentence before.
18. **`ChatLanguageDetector` now requires a confidence margin, not just the top result** -
    reported Spanish getting misdetected as Italian. `MIN_LENGTH_FOR_DETECTION` already guards
    against short text where the top result is wrong by a wide, confident margin, but closely
    related Romance languages can still end up in a near-tie at 20+ characters with the wrong
    one narrowly on top - a different failure mode. `detect()` now requires the top candidate to
    lead the runner-up by `MIN_CONFIDENCE_MARGIN` (0.15); closer ties return null (not detected)
    instead of guessing. Not tuned against a specific repro - the margin prints on every call, so
    a recurrence will show the actual numbers to tune against.
19. **Added a "Download all" button** above the language pack list, and a "Show flags in chat"
    checkbox in the side panel (the underlying config option already existed but wasn't exposed
    there, unlike auto-detect/auto-update which already had their own checkboxes). Also raised
    the "Translated messages" log height from 300 to 500px - a handful of entries were already
    requiring a scroll within that section.
20. **Full visual redesign of the side panel**, from a reference mockup: near-black background,
    violet accent, rounded cards/pills instead of RuneLite's default square dark-gray/orange
    styling, uppercase muted section labels, and a timestamp on every log entry. Swing has no
    built-in rounded-rect painting, so `RoundedPanel`/`PillButton` were added as small reusable
    custom-painted components; `JComboBox` was recolored to match but keeps square corners (a
    faithful rounded combo box needs a custom `ComboBoxUI` delegate, judged out of scope for
    this pass). Pack rows collapsed from two buttons (Incoming/Outgoing) to one "Download" that
    fetches both directions together - downloading only one direction was confirmed to not be a
    state anyone wants - plus a "Delete" button with a confirmation dialog, and every successful
    download/delete now posts an in-game chat confirmation (there was previously no feedback at
    all that a background download had actually finished).
21. **Non-Latin-script languages re-added, with romanized outgoing translations** - Arabic,
    Russian, Ukrainian, Hindi, Chinese, Japanese, and Korean, previously removed outright (see
    "Language pack coverage" below) because a correctly-translated non-Latin message still
    rendered as `?` boxes in the actual OSRS chatbox (the client's own font has no glyphs for
    these scripts). Rather than dropping the languages, `Language.usesLatinScript()` now
    marks which ones need it, and `TranslationEngine.translateFromEnglish()` runs non-Latin
    output through ICU4J's `Transliterator` ("Any-Latin; Latin-ASCII") before returning it -
    e.g. English "hello" -> Arabic "مرحبا" -> romanized to something like "mrhba" before it's
    ever written to the chatbox. This only applies to the *outgoing* direction; incoming
    messages in these languages still translate and detect normally, since the side panel log
    is ordinary Java-rendered text with no font restriction. Repo names for the new packs were
    verified live against huggingface.co, not guessed - Japanese in particular has an
    asymmetric upstream naming quirk (`opus-mt-ja-en` for to-English, but `opus-mt-en-jap`, not
    `-ja`, for from-English), and Korean has no working from-English model published upstream at
    all (same situation as Polish, just for a non-Latin script). New flag badges added for all
    seven. `./gradlew romanizerSmokeTest` verifies the transliteration step itself produces
    plain-ASCII output for a sample of each script, with no network or downloaded model needed.
    Also caught live by making `ChatLanguageDetector`'s profile loading defensive (per-locale,
    not one `readBuiltIn(fullList)` call - see that class): the bundled detector library has no
    plain "zh" profile, only region-qualified "zh-CN"/"zh-TW", so Chinese detection would have
    silently never fired at all had the loading not been made per-locale. Requesting "zh-CN"
    specifically fixed it without affecting `Language.fromCode()` afterward, since
    `LdLocale.getLanguage()` strips the region back off.
22. **Three real UI bugs from a live screenshot of the redesigned panel** (item 20): checkbox
    checkmarks were invisible (the default look-and-feel draws them in a dark, LAF-dependent
    color with no simple override, indistinguishable from unchecked against this panel's
    near-black background - fixed with a hand-painted `CheckboxIcon`); combo box text was
    garbled ("Inglish" instead of "English") because `Language.toString()`'s flag emoji hit the
    exact same multi-codepoint rendering problem `FlagIconFactory` already exists to work around
    elsewhere, just missed for combo boxes (fixed with `LanguageComboBoxRenderer`, drawing a
    flag icon + plain text instead); and the "Installed language packs" header's "Download all"
    text button was overlapping into illegible jammed text at this panel's width (`BorderLayout`
    doesn't shrink/wrap either side, it just overlaps once combined width exceeds the
    container) - replaced with a small icon-only `DownloadIcon` button, matching the reference
    mockup's own icon-only treatment there. Also proactively swapped the "Clear" button's "🗑"
    emoji for a drawn `TrashIcon` on the same reasoning, before it was confirmed broken by a
    screenshot rather than after.
23. **Pack row Download/Delete buttons were invisible** - a second live screenshot after item 22
    showed rows rendering as just a flag+name line with no buttons and no way to tell which
    packs were downloaded. `PillButton` used `setContentAreaFilled(false)` +
    `setOpaque(false)` to paint a custom rounded pill instead of the look-and-feel's own square
    background - a normally-safe Swing pattern, but the *only* piece of this redesign using that
    specific combination, versus the custom-`Icon`-via-`setIcon()` technique that item 22's
    (confirmed working) checkbox/combo-box fixes both used instead. Rewritten to stay opaque
    (the look-and-feel default) and paint the rounded pill on top of the normal square
    background instead of replacing it, with the button's own background color matched to its
    container so the unrounded corners hide underneath. Also renamed "Installed language packs"
    back to "Language packs" - copied from the reference mockup without noticing the list shows
    every available language, not just downloaded ones, which read as misleading.

**Not yet verified - confirm on your own client:**
- The redesigned translate hotkey now that channel-prefix preservation and incoming detection
  are both fixed - full end-to-end test across public chat, PMs, friends chat, and clan chat.
- The public/clan/FC chatbox display lag when using the translate hotkey - confirmed the
  underlying translation and send are both correct, but the box itself doesn't visually update
  before you press Enter (unlike PMs, which do). Two rebuild-script attempts
  (`CHAT_TEXT_INPUT_REBUILD`, `BUILD_CHATBOX`) plus a same-tick-ordering fix (deferring the
  rebuild to the next tick via `invokeLater`) haven't confirmed-fixed it yet.
- The `MenuOpened`-based chatbox right-click "Translate" (item 15, second rewrite) - not yet
  exercised live at all. Needs confirmation that `client.getWidget(WidgetInfo.CHATBOX_MESSAGE_LINES)`
  actually resolves to a real widget with children (watch for `chatbox message-lines widget ...
  has no children` in the console - if that prints, the widget assumption itself is wrong), that
  the widget-to-`MessageNode` text-matching in `findChatLine()` finds a match (watch for `chat
  line under cursor but no matching MessageNode found`), and that right-clicking works both on
  the name portion and elsewhere in the message text on the same line.
- The Spanish/Italian detection margin fix (item 18), the "Show flags in chat" panel checkbox /
  taller log (item 19), and the visual redesign (item 20) - none exercised live yet.
- The romanized non-Latin-script languages (item 21) - the romanization step itself is verified
  in isolation (`./gradlew romanizerSmokeTest`), but the full pipeline (download a pack, detect
  incoming Arabic/Russian/etc. chat, translate it outgoing, confirm the romanized result both
  writes into the chatbox correctly *and* actually sends without the game rejecting/mangling
  it) has not been exercised against a real client at all.
- Translation quality on further languages/sentences - greedy decoding (not beam search) was a
  deliberate v1 simplicity tradeoff, so expect occasional rougher phrasing on longer or more
  ambiguous input than the short chat-style lines tested above.

**First things to do locally:** `./gradlew runOfflineTranslate`, log in, download a pack from
the side panel, and try `/t` with a friend or alt account - that exercises the two pieces that
couldn't be tested without a real client.

## Language pack coverage

Two categories, split by `Language.usesLatinScript()`:

- **Latin-script languages** (Spanish, French, German, Italian, Dutch, Polish, Swedish, Finnish,
  Danish, Czech, Vietnamese, Indonesian, Hungarian, Afrikaans) translate normally in both
  directions - the OSRS chat font renders them correctly as-is.
- **Non-Latin-script languages** (Arabic, Russian, Ukrainian, Hindi, Chinese, Japanese, Korean)
  were originally left out entirely: the OSRS client's own chat font has no glyphs for these
  scripts (confirmed live - a correctly translated Arabic message rendered as `?` in the actual
  game chat, even though the translation itself was exactly right). Rather than dropping them,
  incoming detection/translation for these languages works exactly like any other (the side
  panel log is ordinary Java-rendered text, unaffected by the game's font), and *outgoing*
  translations into them get romanized - converted to plain Latin letters via ICU4J - before
  being written to the chatbox, so they're guaranteed renderable there too, at the cost of some
  transliteration precision (it's a general script-to-Latin approximation, not a proper
  per-language romanization scheme).

Every pack is English-paired (translating between two non-English languages pivots through
English). Not every language has a model in both directions - this reflects real gaps in the
upstream [Helsinki-NLP OPUS-MT](https://huggingface.co/Helsinki-NLP) models, not an
implementation limitation:

| Language | Incoming (→ English) | Outgoing (English →) |
|---|---|---|
| Spanish, French, German, Italian, Dutch, Swedish, Finnish, Danish, Czech, Vietnamese, Indonesian, Hungarian, Afrikaans | ✅ | ✅ |
| Polish, Korean | ✅ | ❌ (no English→ model published upstream) |
| Arabic, Russian, Ukrainian, Hindi, Chinese, Japanese | ✅ | ✅ (romanized to Latin letters before reaching the chatbox) |

Vietnamese is Latin-script but diacritic-heavy (tone marks) - more likely than the others here
to include characters outside the client font's coverage. Left in, but untested either way.

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
