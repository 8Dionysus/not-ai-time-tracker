#!/usr/bin/env python3
"""Activity icon set: one source for app, notification and widget drawables.

Icons are 24-unit line drawings (round caps/joins). The catalog mapping uses the
semantic keys from ActivityIcons.resolve: "type:<id>:v<meaningVersion>", ancestors
are the fallback, so mapping area roots covers every catalog type.

  python3 scripts/activity_icons.py --check            validate ids against catalog/runtime-catalog.json
  python3 scripts/activity_icons.py --write            write res/drawable/act_*.xml and ActivityGlyphs.kt
  python3 scripts/activity_icons.py --preview out.png  contact sheet (needs cairosvg)
"""
import argparse, collections, json, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
STROKE = 1.7


def circle(cx, cy, r):
    return f"M{cx - r:g} {cy:g}a{r:g} {r:g} 0 1 0 {2 * r:g} 0a{r:g} {r:g} 0 1 0 {-2 * r:g} 0z"


def dot(cx, cy, r=1.15):
    return ("fill", circle(cx, cy, r))


def dashed_ring(cx, cy, r, n=8, fill_ratio=0.55):
    import math
    out = []
    step = 2 * math.pi / n
    for i in range(n):
        a0 = i * step - math.pi / 2
        a1 = a0 + step * fill_ratio
        x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
        x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
        out.append(f"M{x0:.2f} {y0:.2f}A{r:g} {r:g} 0 0 1 {x1:.2f} {y1:.2f}")
    return out


# name -> paths. A plain string is a stroke; ("fill", d) is a filled shape.
ICONS = {
    "work": ["M6.5 7.5h11a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17v-7a2.5 2.5 0 0 1 2.5-2.5z",
             "M9 7.5V6a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 6v1.5", "M4 13h16"],
    "focus": [circle(12, 12, 8), circle(12, 12, 4), dot(12, 12, 1.1)],
    "meeting": ["M4 6.5A2.5 2.5 0 0 1 6.5 4h7A2.5 2.5 0 0 1 16 6.5v3.5a2.5 2.5 0 0 1-2.5 2.5H9.5L6 15.5v-3.1A2.5 2.5 0 0 1 4 10z",
                "M19 9.6a2.5 2.5 0 0 1 1 2v3.4a2.5 2.5 0 0 1-2 2.45V20l-3.5-2.5H12a2.5 2.5 0 0 1-2.2-1.3"],
    "mail": ["M5.5 6h13A2 2 0 0 1 20.5 8v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M4 7.5l8 6 8-6"],
    "code": ["M8.5 7L3.5 12l5 5", "M15.5 7l5 5-5 5", "M13.5 5l-3 14"],
    "chart": ["M4 4v16h16", "M7.5 15l3.5-4 3 2.5 5-6.5"],
    "server": ["M5.5 4.5h13a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 9V6a1.5 1.5 0 0 1 1.5-1.5z",
               "M5.5 13.5h13a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18v-3a1.5 1.5 0 0 1 1.5-1.5z",
               dot(7.5, 7.5, 1), dot(7.5, 16.5, 1)],
    "language": ["M5 4.5h14a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-7l-4.5 3.5V17H5a2 2 0 0 1-2-2V6.5a2 2 0 0 1 2-2z",
                 "M9 14l3-6.5 3 6.5", "M10.1 11.8h3.8"],
    "cards": ["M8 5.2l8.3-1.5a1.5 1.5 0 0 1 1.75 1.2l1.9 10.4a1.5 1.5 0 0 1-1.2 1.75l-.75.13",
              "M5.5 8h8.5A1.5 1.5 0 0 1 15.5 9.5v9a1.5 1.5 0 0 1-1.5 1.5H5.5A1.5 1.5 0 0 1 4 18.5v-9A1.5 1.5 0 0 1 5.5 8z"],
    "headphones": ["M4.5 15v-3a7.5 7.5 0 0 1 15 0v3",
                   "M4.5 14.5H6a1.5 1.5 0 0 1 1.5 1.5v2.5A1.5 1.5 0 0 1 6 20h-.5a1 1 0 0 1-1-1z",
                   "M19.5 14.5H18a1.5 1.5 0 0 0-1.5 1.5v2.5A1.5 1.5 0 0 0 18 20h.5a1 1 0 0 0 1-1z"],
    "music": ["M9 17.5V6.2l10-2.2v11.5", "M9 9.3l10-2.2", circle(6.6, 17.5, 2.4), circle(16.6, 15.5, 2.4)],
    "book": ["M12 6.5c-1.8-1.3-4.3-2-7.5-2v13c3.2 0 5.7.7 7.5 2 1.8-1.3 4.3-2 7.5-2v-13c-3.2 0-5.7.7-7.5 2z", "M12 6.5v13"],
    "article": ["M7 3.5h7l4.5 4.5v11a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z",
                "M14 3.5V8h4.5", "M8.5 12.5h7", "M8.5 16h5"],
    "pen": ["M15 4.5l4.5 4.5L9 19.5l-5 1 1-5z", "M13 6.5l4.5 4.5"],
    "study": ["M2.5 9.5L12 5l9.5 4.5L12 14z", "M6.5 11.6v4.4c1.5 1.3 3.4 2 5.5 2s4-.7 5.5-2v-4.4", "M21.5 9.5v5"],
    "teach": ["M3.5 4.5h17", "M5 4.5v10h14v-10", "M9 19.5l3-5 3 5", "M8 11l2.5-2.2 2 1.6 3.5-3"],
    "flask": ["M9.5 3.5h5", "M10.5 3.5v5.3l-5.2 8.8A1.9 1.9 0 0 0 7 20.5h10a1.9 1.9 0 0 0 1.7-2.9l-5.2-8.8V3.5", "M7.6 14.5h8.8"],
    "run": [circle(15, 4.9, 1.9), "M7 10.2l3.2-2.3h4.2l2 3.3 2.8.6", "M14.4 7.9l-3 5.8 3.6 2.6-1.3 4.2",
            "M11.4 13.7l-1.9 2.8H5.6"],
    "strength": ["M7.5 12h9", "M4.5 9v6", "M7.5 6.5v11", "M16.5 6.5v11", "M19.5 9v6"],
    "cardio": ["M12 19.5s-7.5-4.6-7.5-10a4.2 4.2 0 0 1 7.5-2.6 4.2 4.2 0 0 1 7.5 2.6c0 5.4-7.5 10-7.5 10z",
               "M7.7 11.6h2.1l1.3-2.1 2 4 1.3-1.9h1.9"],
    "lotus": ["M12 5.5c1.9 1.8 2.9 4 2.9 6.4S13.9 16.4 12 18c-1.9-1.6-2.9-3.7-2.9-6.1s1-4.6 2.9-6.4z",
              "M9.3 9.4C7.2 8.8 5 8.9 3.5 9.6c.3 3.9 2.9 7.6 8.5 8.4",
              "M14.7 9.4c2.1-.6 4.3-.5 5.8.2-.3 3.9-2.9 7.6-8.5 8.4"],
    "bike": [circle(6, 15.5, 3.5), circle(18, 15.5, 3.5), "M6 15.5l3.8-7h5.2l3 7", "M9.8 8.5l2.7 7h-6.5",
             "M8.3 6h3", "M15 8.5l-.9-2.5h2.2"],
    "water": ["M3 9.5c1.5 0 1.5-1.5 3-1.5s1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5",
              "M3 15c1.5 0 1.5-1.5 3-1.5s1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5"],
    "ball": [circle(12, 12, 8), "M5.8 7c2.6 1.6 4 3.2 4 5s-1.4 3.4-4 5", "M18.2 7c-2.6 1.6-4 3.2-4 5s1.4 3.4 4 5"],
    "mountain": ["M3 19.5l6.5-11 4 6.6 2.4-3.6 5.1 8z", "M7.8 11.4l1.7 1.3 1.6-1.4"],
    "snow": ["M12 3.5v17", "M4.6 7.75l14.8 8.5", "M4.6 16.25l14.8-8.5", "M9.8 4.8L12 6.8l2.2-2", "M9.8 19.2l2.2-2 2.2 2"],
    "steps": ["M8.5 3.5c1.6 0 2.4 1.7 2.4 3.8s-.8 3.8-2.4 3.8-2.4-1.7-2.4-3.8.8-3.8 2.4-3.8z",
              "M6.9 13.2h3.2v1.6a1.6 1.6 0 0 1-3.2 0z",
              "M15.5 8c1.6 0 2.4 1.7 2.4 3.8s-.8 3.8-2.4 3.8-2.4-1.7-2.4-3.8.8-3.8 2.4-3.8z",
              "M13.9 17.7h3.2v1.2a1.6 1.6 0 0 1-3.2 0z"],
    "leaf": ["M5 19c0-8 5-13.5 14.5-14.5C19 14 13.5 19 5 19z", "M5 19l8.5-8.5"],
    "home": ["M4 10.5L12 4l8 6.5", "M6 9v9.5A1.5 1.5 0 0 0 7.5 20h9a1.5 1.5 0 0 0 1.5-1.5V9", "M10 20v-5h4v5"],
    "clean": ["M10 4c.6 3.3 2.2 4.9 5.5 5.5-3.3.6-4.9 2.2-5.5 5.5-.6-3.3-2.2-4.9-5.5-5.5C7.8 8.9 9.4 7.3 10 4z",
              "M17.5 13.5c.3 1.6 1.1 2.4 2.7 2.7-1.6.3-2.4 1.1-2.7 2.7-.3-1.6-1.1-2.4-2.7-2.7 1.6-.3 2.4-1.1 2.7-2.7z"],
    "cook": ["M5 11h14v5.5a3.5 3.5 0 0 1-3.5 3.5h-7A3.5 3.5 0 0 1 5 16.5z", "M5 13H3", "M19 13h2",
             "M4.5 11h15", "M9.5 4.5c-.7.8-.7 1.7 0 2.5", "M14.5 4.5c-.7.8-.7 1.7 0 2.5"],
    "food": ["M7 3.5v5.5a2 2 0 0 0 4 0V3.5", "M9 3.5v17", "M17 20.5V3.5c-1.9 1.2-3 3.4-3 6.5v3.5h3"],
    "cup": ["M5 9h11v5.5a4.5 4.5 0 0 1-4.5 4.5h-2A4.5 4.5 0 0 1 5 14.5z", "M16 10.5h1.5a2.5 2.5 0 0 1 0 5H16",
            "M8.5 3.5c-.7.8-.7 1.7 0 2.5", "M12.5 3.5c-.7.8-.7 1.7 0 2.5"],
    "shirt": ["M8.5 4L4 6.5l1.5 4 2.5-1V20h8V9.5l2.5 1 1.5-4L15.5 4c-.5 1.4-1.9 2.3-3.5 2.3S9 5.4 8.5 4z"],
    "tools": ["M15.5 3.8a4.5 4.5 0 0 0-4.3 5.9L4.6 16.3a2 2 0 0 0 2.8 2.8l6.6-6.6a4.5 4.5 0 0 0 5.9-4.3l-2.6 2.6-2.4-.6-.6-2.4z"],
    "paw": ["M12 13c2.6 0 5 2.6 5 4.8 0 1.4-1.1 2.2-2.5 2.2-1 0-1.6-.5-2.5-.5s-1.5.5-2.5.5C8.1 20 7 19.2 7 17.8 7 15.6 9.4 13 12 13z",
            circle(5.8, 10.2, 1.6), circle(9.3, 6.5, 1.7), circle(14.7, 6.5, 1.7), circle(18.2, 10.2, 1.6)],
    "bag": ["M5.5 8h13l-1 11.5a1 1 0 0 1-1 .9h-9a1 1 0 0 1-1-.9z", "M9 10.5V7a3 3 0 0 1 6 0v3.5"],
    "papers": ["M8.5 5H7a1.5 1.5 0 0 0-1.5 1.5v12A1.5 1.5 0 0 0 7 20h10a1.5 1.5 0 0 0 1.5-1.5v-12A1.5 1.5 0 0 0 17 5h-1.5",
               "M9.5 3.5h5a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-1a1 1 0 0 1 1-1z", "M9 11h6", "M9 14.5h4"],
    "card": ["M4.5 6h15A1.5 1.5 0 0 1 21 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16.5v-9A1.5 1.5 0 0 1 4.5 6z",
             "M3 10.5h18", "M6.5 14.5h3"],
    "drop": ["M12 3.5c3.5 4.3 5.5 7.6 5.5 10.5a5.5 5.5 0 0 1-11 0c0-2.9 2-6.2 5.5-10.5z"],
    "heart": ["M12 19.5s-7.5-4.6-7.5-10a4.2 4.2 0 0 1 7.5-2.6 4.2 4.2 0 0 1 7.5 2.6c0 5.4-7.5 10-7.5 10z"],
    "medical": [circle(12, 12, 8.5), "M12 8.5v7", "M8.5 12h7"],
    "sofa": ["M5 11V8.5A2.5 2.5 0 0 1 7.5 6h9A2.5 2.5 0 0 1 19 8.5V11",
             "M3 13a2 2 0 0 1 4 0v1.5h10V13a2 2 0 0 1 4 0v4.5H3z", "M5 17.5v2", "M19 17.5v2"],
    "sleep": ["M17.5 15.6A7.2 7.2 0 0 1 8.4 6.5a7.2 7.2 0 1 0 9.1 9.1z", "M15 3.5h4l-4 4.5h4"],
    "bus": ["M6.5 3.5h11A1.5 1.5 0 0 1 19 5v11.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 16.5V5a1.5 1.5 0 0 1 1.5-1.5z",
            "M5 11h14", "M7.5 18v2.5", "M16.5 18v2.5", dot(8.3, 14.6, 1), dot(15.7, 14.6, 1)],
    "hourglass": ["M7 3.5h10", "M7 20.5h10", "M8 3.5V6c0 2.3 4 3.8 4 6s-4 3.7-4 6v2.5", "M16 3.5V6c0 2.3-4 3.8-4 6s4 3.7 4 6v2.5"],
    "phone": ["M7 3.8l2.3-.2 1.6 3.9-1.9 1.4a11 11 0 0 0 5.6 5.6l1.4-1.9 3.9 1.6-.2 2.3a2 2 0 0 1-2 1.8A14.5 14.5 0 0 1 5.2 5.8a2 2 0 0 1 1.8-2z"],
    "screen": ["M5 5h14a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 19 17H5a1.5 1.5 0 0 1-1.5-1.5v-9A1.5 1.5 0 0 1 5 5z",
               "M10.5 8.6v4.8l4-2.4z", "M9 20.5h6"],
    "feed": ["M8 3.5h8A1.5 1.5 0 0 1 17.5 5v14a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5z",
             "M9.5 8h5", "M9.5 11.5h5", "M9.5 15h3"],
    "gamepad": ["M7.5 7.5h9a4 4 0 0 1 3.9 3.1l.9 4.3a2.6 2.6 0 0 1-4.5 2.3L15 15.5H9l-1.8 1.7a2.6 2.6 0 0 1-4.5-2.3l.9-4.3a4 4 0 0 1 3.9-3.1z",
                "M8 10v3", "M6.5 11.5h3", dot(15.6, 10.4, 1), dot(17.2, 12.6, 1)],
    "dice": ["M6.5 4h11A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5v-11A2.5 2.5 0 0 1 6.5 4z",
             dot(8.7, 8.7), dot(12, 12), dot(15.3, 15.3)],
    "palette": ["M12 3.5a8.5 8.5 0 1 0 0 17c1.2 0 1.8-.8 1.8-1.7 0-1.2-1-1.6-1-2.6 0-1 .8-1.7 1.8-1.7h2.2a3.7 3.7 0 0 0 3.7-3.7c0-4.3-3.8-7.3-8.5-7.3z",
                dot(7.6, 11.2, 1.05), dot(9.8, 7.6, 1.05), dot(14.3, 7.6, 1.05)],
    "scissors": [circle(6.5, 17.5, 2.5), circle(6.5, 6.5, 2.5), "M8.6 8l11 9.5", "M8.6 16l11-9.5"],
    "camera": ["M4.5 7.5h3L9 5h6l1.5 2.5h3A1.5 1.5 0 0 1 21 9v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V9a1.5 1.5 0 0 1 1.5-1.5z",
               circle(12, 13.3, 3.5)],
    "mic": ["M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z", "M6.5 11a5.5 5.5 0 0 0 11 0", "M12 16.5v4", "M9 20.5h6"],
    "cube": ["M12 3.5l7.5 4.2v8.6L12 20.5l-7.5-4.2V7.7z", "M4.5 7.7L12 12l7.5-4.3", "M12 12v8.5"],
    "columns": ["M12 3.8L20 8.5H4z", "M6.5 11v6.5", "M10.3 11v6.5", "M13.7 11v6.5", "M17.5 11v6.5", "M3.5 20.5h17"],
    "candle": ["M9.5 11h5v9.5h-5z", "M12 3.5c1.6 1.7 2.3 3 2.3 4.2a2.3 2.3 0 0 1-4.6 0c0-1.2.7-2.5 2.3-4.2z"],
    "ticket": ["M4 7.5A1.5 1.5 0 0 1 5.5 6h13A1.5 1.5 0 0 1 20 7.5V10a2 2 0 0 0 0 4v2.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 16.5V14a2 2 0 0 0 0-4z",
               "M14.5 6v2", "M14.5 11v2", "M14.5 16v2"],
    "shield": ["M12 3.5l7 2.5v5.5c0 4.4-2.9 7.6-7 9-4.1-1.4-7-4.6-7-9V6z"],
    "bolt": ["M13 3L5.5 13.5H12l-1 7.5 7.5-10.5H12z"],
    "family": [circle(9, 6.5, 2.4), "M4.8 20.5v-4.3a4.2 4.2 0 0 1 8.4 0v4.3", circle(17, 11.3, 1.8), "M14.4 20.5v-2.3a2.6 2.6 0 0 1 5.2 0v2.3"],
    "people": [circle(9, 8, 3), "M3.5 19.5c0-3.2 2.5-5.5 5.5-5.5s5.5 2.3 5.5 5.5", circle(16.5, 8.5, 2.4),
               "M15.5 14.1c.3-.1.7-.1 1-.1 2.6 0 4.5 2.1 4.5 5"],
    # Fallbacks from ActivityIcons: card without a mapped type, or nothing chosen.
    "custom": [circle(12, 12, 7), dot(12, 12, 1.4)],
    "unclassified": dashed_ring(12, 12, 7.5),
}

# Catalog type id -> icon. Ancestors are the fallback, so roots must all be covered.
MAP = {
    # roots
    "area.work": "work", "area.work_coordination": "work", "area.work_business": "work", "area.professional_service_roles": "work",
    "area.work_people_and_leadership": "people", "area.work_admin_finance_compliance": "papers",
    "area.software_engineering": "code", "area.it_infrastructure": "server", "area.data_analytics": "chart",
    "area.analysis_decision": "chart", "area.knowledge_research": "flask", "area.design_product": "palette",
    "area.language_practice": "language", "area.language_learning": "language",
    "area.learning": "study", "area.education_formal": "study", "area.education_self_directed": "study", "area.teaching": "teach",
    "area.reading": "book", "area.writing": "pen", "area.writing_editing_translation": "pen",
    "area.media": "screen", "area.leisure": "sofa", "area.unstructured_leisure": "sofa", "area.games": "gamepad",
    "area.cultural_visits": "ticket", "area.social": "people", "area.civic_spiritual": "columns",
    "area.creative": "palette", "area.self_directed_creation": "palette", "area.digital_visual_creation": "palette",
    "area.digital_audio_music": "music", "area.digital_video_motion": "camera", "area.digital_3d_interactive": "cube",
    "area.movement": "run", "area.outdoor_leisure": "mountain", "area.household": "home", "area.self_care": "heart",
    "area.food": "food", "area.sleep": "sleep", "area.mobility": "bus", "area.errands": "bag", "area.caregiving": "family",
    "area.custom": "custom", "area.healthcare_and_care_work": "medical", "area.safety_and_protection": "shield",
    "area.legal_and_public_administration": "columns", "area.field_agriculture_environment": "leaf",
    "area.extraction_energy_operations": "bolt", "area.production_fabrication": "tools", "area.trade_construction": "tools",
    "area.service_and_manual_operations": "tools", "area.logistics_transport_operations": "bus",
    "area.maritime_and_aviation_operations": "bus",
    "group.outdoor.citizen_science": "leaf", "group.creative.collecting": "cards",
    "proposed.craft.quilt_project": "scissors", "proposed.movement.relay_participation": "run",
    # work
    "activity.work.admin": "mail", "activity.work.focused_work": "focus", "activity.work.meeting": "meeting",
    # language
    "activity.language.flashcards": "cards", "activity.language.vocabulary": "cards",
    "activity.language.listening_extensive": "headphones", "activity.language.listening_intensive": "headphones",
    "activity.language.dictation": "headphones", "activity.language.exposure_passive": "headphones",
    "activity.language.reading_extensive": "book", "activity.language.reading_intensive": "book",
    "activity.language.writing": "pen", "activity.language.immersion_media": "screen", "activity.language.shadowing": "mic",
    "activity.language.pronunciation": "mic",
    # learning
    "activity.learning.focused_study": "focus", "activity.learning.class": "teach", "activity.learning.course": "study",
    "activity.learning.research": "flask", "activity.learning.take_notes": "pen", "activity.learning.homework": "papers",
    "activity.learning.recall_practice": "cards", "activity.learning.spaced_review": "cards",
    "activity.learning.watch_lesson": "screen", "activity.learning.listen_lesson": "headphones",
    "activity.learning.code_practice": "code", "activity.learning.study_group": "people",
    # reading, writing
    "activity.reading.article": "article", "activity.reading.reference": "article",
    "activity.writing.correspondence": "mail", "activity.writing.notes": "pen",
    # media
    "activity.media.listen_audio": "headphones", "activity.media.listen_music": "music", "activity.media.watch_film": "screen",
    "activity.media.watch_video": "screen", "activity.media.browse_media": "feed", "group.media.listen": "headphones",
    "group.media.watch": "screen", "group.media.browse": "feed", "group.media.read": "book",
    # leisure, games
    "activity.leisure.game": "gamepad", "activity.leisure.outdoor_leisure": "steps", "activity.leisure.unstructured_rest": "sofa",
    "activity.errands.market_window_browsing": "bag",
    "group.leisure_sport.games.d1_nastolnye_kartochnye_i_tabletop_igry": "dice",
    "group.leisure_sport.games.d2_golovolomki_solo_igry_slovesnye_zanyatiya": "dice",
    "group.leisure_sport.games.d3_tsifrovye_igry": "gamepad",
    "group.leisure_sport.unstructured_leisure.g.quiet_rest": "sofa",
    # social
    "activity.social.call_or_video": "phone", "activity.social.messaging": "mail", "activity.social.personal_correspondence": "mail",
    "activity.social.talking": "meeting",
    # household
    "activity.household.clean": "clean", "activity.household.cleaning_other": "clean", "activity.household.indoor_cleaning": "clean",
    "activity.household.outdoor_cleaning": "clean", "activity.household.meal_cleanup": "clean",
    "activity.household.cook": "cook", "activity.household.meal_preparation": "cook", "activity.household.food_other": "cook",
    "activity.household.menu_planning": "cook", "activity.household.meal_serving": "food",
    "activity.household.laundry": "shirt", "activity.household.textile_washing": "shirt", "activity.household.textile_drying": "shirt",
    "activity.household.textile_ironing_folding": "shirt", "activity.household.textile_mending": "shirt",
    "activity.household.textile_other": "shirt", "activity.household.event_clothing_prep": "shirt",
    "activity.household.home_maintenance": "tools", "activity.household.diy_other": "tools", "activity.household.dwelling_diy": "tools",
    "activity.household.goods_ict_repair": "tools", "activity.household.vehicle_maintenance": "tools",
    "activity.household.heating_water_system": "tools",
    "activity.household.pet_care": "paw", "activity.household.pet_daily_care": "paw", "activity.household.pet_other": "paw",
    "activity.household.pet_service": "paw", "activity.household.garden": "leaf", "activity.household.plants_upkeep": "leaf",
    "activity.household.household_admin": "papers", "activity.household.plan_duties": "papers",
    "activity.household.pay_bills": "card",
    # self care, food, sleep
    "activity.self_care.hygiene": "drop", "activity.self_care.personal_hygiene_detail": "drop",
    "activity.self_care.appointment_or_treatment": "medical", "activity.self_care.own_health": "medical",
    "activity.self_care.receive_health_care": "medical", "activity.self_care.health_routine": "lotus",
    "activity.self_care.recovery": "lotus", "activity.self_care.solitude": "lotus",
    "activity.food.drink": "cup", "activity.food.drinking_detail": "cup",
    # mobility, errands
    "activity.mobility.walk_to_destination": "steps", "activity.mobility.waiting_in_trip": "hourglass",
    "activity.errands.shopping": "bag", "activity.errands.goods_purchase": "bag", "activity.errands.queue": "hourglass",
    "activity.errands.delivery_wait": "hourglass", "activity.errands.access_support_wait": "hourglass",
    "activity.errands.personal_admin": "papers", "activity.errands.government_service": "columns",
    "activity.errands.legal_service": "papers", "activity.errands.bank_service": "card",
    "activity.errands.appointment_or_service": "papers",
    # caregiving, civic
    "activity.caregiving.adult_emotional_support": "heart", "activity.caregiving.companionship": "heart",
    "activity.civic.private_spiritual_practice": "candle", "activity.civic.spiritual_practice": "candle",
    "activity.civic.religious_service": "candle", "activity.civic.collective_religious_practice": "candle",
    "activity.civic.conduct_religious_ritual": "candle", "activity.civic.religious_other": "candle",
    "activity.civic.volunteer": "heart",
    # creative
    "activity.creative.craft": "scissors", "activity.creative.draw": "palette", "activity.creative.instrument": "music",
    "activity.creative.make_or_repair": "tools", "activity.creative.photography": "camera", "activity.creative.rehearse": "mic",
    "group.leisure_sport.creative.c1_vizualnoe_iskusstvo_i_izobrazhenie": "palette",
    "group.leisure_sport.creative.c2_plastika_keramika_podelki_i_rabota_s_materialom": "scissors",
    "group.leisure_sport.creative.c3_tekstil_volokno_i_shite": "scissors",
    "group.leisure_sport.creative.c4_diy_maker_praktiki_sozdanie_i_pochinka": "tools",
    "group.leisure_sport.creative.c5_muzyka_zvuk_i_stsenicheskie_praktiki": "music",
    "group.leisure_sport.creative.c6_tekstovoe_i_setevoe_samovyrazhenie": "pen",
    "activity.audio.record": "mic", "activity.audio.podcast_host": "mic", "activity.audio.rehearse": "mic",
    # software
    "activity.software.debug": "code", "activity.software.monitor": "chart", "activity.software.test_performance": "chart",
    "activity.software.database_work": "server", "activity.software.deploy": "server", "activity.software.incident": "shield",
    "activity.software.test_security": "shield", "activity.software.review_code": "code", "activity.software.specify": "article",
    # movement
    "activity.movement.cardio": "cardio", "activity.movement.strength": "strength", "activity.movement.exercise_snack": "strength", "activity.movement.mobility": "lotus",
    "activity.movement.hike_training": "mountain", "activity.movement.sport_practice": "ball",
    "group.leisure_sport.movement.a1.kardio_i_aerobnaya_rabota": "cardio",
    "group.leisure_sport.movement.a1.sila_i_fizpodgotovka": "strength",
    "group.leisure_sport.movement.a1.gibkost_kontrol_tela_mindful_movement": "lotus",
    "group.leisure_sport.movement.a1.reabilitatsionnaya_vosstanovitelnaya_fizicheskaya_praktika": "lotus",
    "group.leisure_sport.movement.a2.velosipednye_distsipliny": "bike",
    "group.leisure_sport.movement.a2.na_nogakh": "steps",
    "group.leisure_sport.movement.a2.voskhozhdeniya_i_stend": "mountain",
    "group.leisure_sport.movement.a5.komandnye_igry_s_myachom": "ball",
    "group.leisure_sport.movement.a5.raketochnye_i_setochnye": "ball",
    "group.leisure_sport.movement.a5.tochnost_metkost_pole_i_individualnaya_tekhnika": "focus",
    "group.movement.water": "water", "group.movement.winter": "snow", "group.movement.sports": "ball",
    "group.movement.fitness": "strength",
    # outdoor
    "group.leisure_sport.outdoor_leisure.b1_nesportivnaya_progulka_i_prebyvanie_snaruzhi": "steps",
    "group.leisure_sport.outdoor_leisure.b2_nablyudenie_i_poznanie_prirody_na_meste": "leaf",
    "group.leisure_sport.outdoor_leisure.b4.recreational_nature": "leaf",
}
PREFIX_MAP = {"compound.": "run", "activity.proposed_sport.": "run"}  # proposed roots without an area


def catalog():
    data = json.load(open(ROOT / "catalog/runtime-catalog.json", encoding="utf-8"))
    return {t["id"]: t for t in data["types"]}


def icon_for(tid, types):
    if tid in MAP:
        return MAP[tid]
    for prefix, icon in PREFIX_MAP.items():
        if tid.startswith(prefix):
            return icon
    return None


def resolved(types):
    """Effective icon per type through the ancestor fallback, as the app resolves it."""
    out = {}
    for tid, t in types.items():
        cur, seen = t, set()
        while cur and cur["id"] not in seen:
            seen.add(cur["id"])
            icon = icon_for(cur["id"], types)
            if icon:
                out[tid] = icon
                break
            cur = types.get(cur.get("parentId"))
    return out


def check(types):
    errors = [f"unknown type id {k}" for k in MAP if k not in types]
    errors += [f"unknown icon {v} for {k}" for k, v in MAP.items() if v not in ICONS]
    eff = resolved(types)
    missing = [t for t in types if t not in eff]
    errors += [f"no icon for {t}" for t in missing]
    used = set(eff.values()) | {"custom", "unclassified"}
    unused = sorted(set(ICONS) - used)
    counts = collections.Counter(eff.values())
    return errors, unused, counts


def drawable(name):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- Generated by scripts/activity_icons.py: activity icon "{name}". Tint at the use site. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"',
             '    android:viewportWidth="24" android:viewportHeight="24">']
    for p in ICONS[name]:
        if isinstance(p, tuple):
            lines.append(f'    <path android:fillColor="#FF000000" android:pathData="{p[1]}" />')
        else:
            lines.append(f'    <path android:strokeColor="#FF000000" android:strokeWidth="{STROKE}" android:strokeLineCap="round"')
            lines.append(f'        android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="{p}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def kotlin(types):
    keys = []
    for tid, icon in sorted(MAP.items()):
        t = types[tid]
        keys.append(f'        "type:{tid}:v{t["meaningVersion"]}" to R.drawable.act_{icon},')
    prefixes = [f'        "{p}" to R.drawable.act_{i},' for p, i in PREFIX_MAP.items()]
    return f'''package dev.notai.tracker.android.ui

import dev.notai.tracker.ActivityIcon
import dev.notai.tracker.android.R

/**
 * Generated by scripts/activity_icons.py from the bundled catalog. Keys are the semantic keys of
 * [dev.notai.tracker.ActivityIcons]: a changed meaning version no longer matches and falls back to the parent.
 */
object ActivityGlyphs {{
    private val byKey: Map<String, Int> = mapOf(
{chr(10).join(keys)}
    )
    /** Types outside the area tree (proposed sport formats) keep a stable family icon. */
    private val byPrefix: List<Pair<String, Int>> = listOf(
{chr(10).join(prefixes)}
    )
    val custom = R.drawable.act_custom
    val unclassified = R.drawable.act_unclassified

    /** First key known to the dictionary, else the semantic fallback. */
    fun resolve(icon: ActivityIcon): Int {{
        icon.keys.forEach {{ key -> byKey[key]?.let {{ return it }} }}
        icon.keys.forEach {{ key ->
            if (key.startsWith("type:")) byPrefix.firstOrNull {{ key.startsWith("type:" + it.first) }}?.let {{ return it.second }}
        }}
        return if (icon.fallback == "activity.unclassified") unclassified else custom
    }}
}}
'''


def preview(out, types):
    import cairosvg, io
    from PIL import Image, ImageDraw, ImageFont
    names = list(ICONS)
    cols, cell = 9, 120
    rows = (len(names) + cols - 1) // cols
    sheets = []
    for bg, fg, sub in [((242, 243, 239), "#1D2024", (94, 100, 110)), ((17, 22, 30), "#ECEAE4", (163, 171, 183))]:
        img = Image.new("RGB", (cols * cell, rows * cell), bg)
        d = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype(str(ROOT / "app/src/main/res/font/onest_400.ttf"), 13)
        except OSError:
            font = ImageFont.load_default()
        for i, n in enumerate(names):
            parts = []
            for p in ICONS[n]:
                if isinstance(p, tuple):
                    parts.append(f'<path d="{p[1]}" fill="{fg}"/>')
                else:
                    parts.append(f'<path d="{p}" fill="none" stroke="{fg}" stroke-width="{STROKE}" stroke-linecap="round" stroke-linejoin="round"/>')
            svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">{"".join(parts)}</svg>'
            big = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=56, output_height=56))).convert("RGBA")
            small = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=20, output_height=20))).convert("RGBA")
            x, y = (i % cols) * cell, (i // cols) * cell
            img.paste(big, (x + 18, y + 18), big)
            img.paste(small, (x + 84, y + 36), small)
            d.text((x + 10, y + 88), n, fill=sub, font=font)
        sheets.append(img)
    w, h = sheets[0].size
    out_img = Image.new("RGB", (w, h * 2))
    out_img.paste(sheets[0], (0, 0)); out_img.paste(sheets[1], (0, h))
    out_img.save(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--preview", type=pathlib.Path)
    a = ap.parse_args()
    types = catalog()
    errors, unused, counts = check(types)
    if errors:
        print("\n".join(errors[:40]), file=sys.stderr)
        print(f"{len(errors)} errors", file=sys.stderr)
        sys.exit(1)
    print(f"{len(ICONS)} icons, {len(MAP)} mapped ids, all {len(types)} catalog types resolve; unused: {unused}")
    if a.write:
        res = ROOT / "app/src/main/res/drawable"
        for old in res.glob("act_*.xml"):
            old.unlink()
        for name in ICONS:
            (res / f"act_{name}.xml").write_text(drawable(name), encoding="utf-8")
        (ROOT / "app/src/main/kotlin/dev/notai/tracker/android/ui/ActivityGlyphs.kt").write_text(kotlin(types), encoding="utf-8")
    if a.preview:
        preview(a.preview, types)


if __name__ == "__main__":
    main()
