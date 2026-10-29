# App copy in twelve languages

All visible app text lives in Android resources. Russian is the source and the default (`values/`); the eleven
other languages follow the in-app language picker (`L` in `app/.../L.kt`), with no activity restart.

## Files

| Path | What it is |
|---|---|
| `catalog.json` | Every string and plural key: its Russian text and the files that use it. |
| `translations/00-editorial-package.json` | Reviewed static copy taken from `docs/localization/tracker-localization-12-locales-editorial.zip`, where the Russian matched exactly. |
| `translations/NN.txt` | Everything else, one key per line: `key ‖ en ‖ es ‖ fr ‖ pt-BR ‖ de ‖ it ‖ ar ‖ zh-Hans ‖ ja ‖ ko ‖ hi`. A plural line lists forms per language, such as `one=%1$d day ¦ other=%1$d days`. These were written by Claude; no native-speaker review is claimed. |
| `translations/NN.json` | Generated from the `.txt` files. |
| `app/src/main/res/values*/ui_strings.xml` | Generated. Do not edit by hand. |

## Workflow

1. Write new copy in Kotlin as `L.s(R.string.key, args…)`, or as a plain Russian literal. Then run
   `python3 scripts/ui_copy/transform.py localization/ui-copy/catalog.json <files>`. This moves each literal into
   the catalog: `${…}` becomes `%N$s`, and `Format.plural(n, "a", "b", "c")` becomes `L.p(R.plurals.…, n)`.
2. Add a line for every new key to a `translations/NN.txt` file.
3. Run `python3 scripts/ui_copy/rows2json.py`. It checks that every language has the same placeholders.
4. Run `python3 scripts/ui_copy/emit.py`. It writes the resources and reports anything missing per language. Lint
   fails if a language is missing a key.

## What is not translated

- What the person writes or names: their own activities, card names, favorites, notes, goal names, and details.
- Preset catalog activities are shown from `catalog/locales/*` (`CatalogNames`) while a record still carries the
  catalog's own wording. The Dataset is never rewritten.
- Technical diagnostics such as exception class names and IDs.

The test `UiScreensTest.languageSweepLeavesNoRussianCopy` switches to English and then Japanese. On each main
screen, sheet and settings page it fails if any line of text, label or content description is still Russian, apart
from the person's own values.
