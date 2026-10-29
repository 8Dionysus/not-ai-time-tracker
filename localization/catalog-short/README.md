# Short catalog names

One line per activity type: `index ‖ en ‖ es ‖ fr ‖ pt-BR ‖ de ‖ it ‖ ar ‖ zh-Hans ‖ ja ‖ ko ‖ hi`.
`index` is the 1-based position in `order.json` (the type tree, depth first). Each name follows the short Russian
catalog name, not the longer reviewed English description; siblings must not share a name in any language.

`scripts/catalog_short_labels.py` turns these files into the locale packs for catalog 0.9.3.0: the new name becomes
the label, the previous label is kept first among the aliases, descriptions stay as reviewed. The same script records
the archived and moved types of that step. Native-speaker review of the short names is still pending.
