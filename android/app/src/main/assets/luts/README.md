# LUT pack

This folder holds the shipped **Picture Perfect X** LUT pack: 100 real 512×512
GPUImage lookup tables plus `manifest.json`, all produced by
[`tools/generate_luts.py`](../../../../../tools/generate_luts.py).

- Each `*.png` is a 64×64×64 identity cube (8×8 grid of 64×64 tiles) with a
  per-profile color grade baked in. The app applies it via `GPUImageLookupFilter`.
- `manifest.json` lists every look: `id`, display `name`, `category`
  (`camera` / `lens` / `film` / `creative`), the `asset` path, and two `swatch`
  colors for the selector chip. `FilterCatalog` reads this at startup.
- At least half the looks are based on prominent **cameras** and **lenses**.

The grades are parametric emulations *inspired by* the named gear and film
stocks — they are not official manufacturer color profiles.

## Regenerating / editing

Edit the profile tables in `tools/generate_luts.py`, then:

```bash
pip install numpy Pillow
python3 android/tools/generate_luts.py
```

This rewrites every PNG and `manifest.json`. To hand-author a look instead, drop
a real 512×512 lookup PNG here and point its manifest `asset` at it.
