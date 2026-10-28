"""Material Symbols Outlined değişken fontunu yalnız kullanılan ikonlara indirger.

Çıktılar:
  app/src/main/res/font/material_symbols_outlined.ttf   (FILL/GRAD/opsz/wght eksenleri korunur)
  app/src/main/java/com/kdgames/wake_on_lan/ui/theme/Sym.kt

Kullanım (android/ klasöründen):  python tools/symbols/subset_symbols.py
Gerekli: pip install fonttools
Tam font (~10 MB) tools/symbols/.cache/ altına indirilir ve git'e girmez.
"""

import pathlib
import sys
import urllib.parse
import urllib.request

from fontTools import subset

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
CACHE = HERE / ".cache"
BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/"
FONT_NAME = "MaterialSymbolsOutlined[FILL,GRAD,opsz,wght]"
OUT_FONT = ANDROID / "app/src/main/res/font/material_symbols_outlined.ttf"
OUT_KT = ANDROID / "app/src/main/java/com/kdgames/wake_on_lan/ui/theme/Sym.kt"


def fetch(suffix: str) -> pathlib.Path:
    CACHE.mkdir(exist_ok=True)
    path = CACHE / (FONT_NAME + suffix)
    if not path.exists():
        url = BASE + urllib.parse.quote(FONT_NAME + suffix)
        print("İndiriliyor:", url)
        urllib.request.urlretrieve(url, path)
    return path


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    font_path = fetch(".ttf")
    codepoints = {}
    for line in fetch(".codepoints").read_text(encoding="utf-8").splitlines():
        name, cp = line.split()
        codepoints[name] = int(cp, 16)

    names = []
    for raw in (HERE / "symbols.txt").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            names.append(line)
    missing = [n for n in names if n not in codepoints]
    if missing:
        print("Bilinmeyen sembol(ler):", ", ".join(missing), file=sys.stderr)
        return 1

    options = subset.Options()
    options.layout_features = ["*"]
    options.notdef_outline = True
    options.name_IDs = ["*"]
    font = subset.load_font(str(font_path), options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=[codepoints[n] for n in names])
    subsetter.subset(font)
    subset.save_font(font, str(OUT_FONT), options)

    lines = [
        "package com.kdgames.wake_on_lan.ui.theme",
        "",
        "// tools/symbols/subset_symbols.py tarafından üretildi; elle düzenlemeyin.",
        "// Kaynak: Material Symbols Outlined (Apache 2.0), tools/symbols/symbols.txt",
        "",
        "/** Material Symbols kod noktaları. [SymbolIcon] ile çizilir. */",
        "@Suppress(\"ObjectPropertyName\", \"ConstPropertyName\")",
        "object Sym {",
    ]
    for n in sorted(set(names)):
        lines.append(f"    const val {n}: String = \"\\u{codepoints[n]:04x}\"")
    lines.append("}")
    OUT_KT.parent.mkdir(parents=True, exist_ok=True)
    OUT_KT.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"{len(set(names))} sembol → {OUT_FONT.name} ({OUT_FONT.stat().st_size} bayt), {OUT_KT.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
