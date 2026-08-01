#!/usr/bin/env python3
"""
Generates the Picture Perfect X LUT pack.

Each output is a 512x512 GPUImage-style lookup PNG (a 64x64x64 identity cube laid
out as an 8x8 grid of 64x64 tiles) with a per-profile color grade baked in. When
the app applies it through GPUImageLookupFilter, the grade is reproduced on the
live camera frame; the filter's own intensity control (0..1) cross-fades it
against the original, which is what the in-app 0-100 slider drives.

The grades are parametric emulations *inspired by* the named cameras, lenses and
film stocks - they are not official manufacturer color profiles.

Run:  python3 android/tools/generate_luts.py
Out:  android/app/src/main/assets/luts/*.png  +  manifest.json
"""

import json
import os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "assets", "luts"))

SIZE = 512
TILE = 64  # cube dimension (64^3), 8x8 tiles of 64x64


def identity_lut():
    """Neutral 512x512 GPUImage lookup image as float RGB in [0,1]."""
    xs = np.arange(SIZE)
    ys = np.arange(SIZE)
    gx, gy = np.meshgrid(xs, ys)  # gx=column (x), gy=row (y)
    r = (gx % TILE) / (TILE - 1)
    g = (gy % TILE) / (TILE - 1)
    b = ((gy // TILE) * 8 + (gx // TILE)) / (TILE - 1)
    return np.stack([r, g, b], axis=-1).astype(np.float32)


def _as3(v):
    if isinstance(v, (list, tuple)):
        return np.array(v, dtype=np.float32)
    return np.array([v, v, v], dtype=np.float32)


# Global richness: push each look's defining traits harder so the pack reads as vibrant and the
# 100 looks are clearly distinct from one another. 1.0 = original grades; >1 = stronger.
RICHNESS = 1.6


def amplify(p):
    """Scale a profile's parameters by RICHNESS so looks are richer and more differentiated.
    Multiplicative params pivot around 1.0; additive params scale from 0."""
    r = RICHNESS
    out = dict(p)
    for k in ("saturation", "contrast", "gamma"):
        if k in out:
            v = out[k]
            if isinstance(v, (list, tuple)):
                out[k] = [1 + (x - 1) * r for x in v]
            else:
                out[k] = 1 + (v - 1) * r
    if "gain" in out:
        g = _as3(out["gain"])
        out["gain"] = [float(1 + (x - 1) * r) for x in g]
    for k in ("temp", "tint", "exposure", "vibrance", "fade",
              "shadow_amt", "highlight_amt", "lift"):
        if k in out:
            v = out[k]
            if isinstance(v, (list, tuple)):
                out[k] = [x * r for x in v]
            else:
                out[k] = v * r
    return out


def luma(rgb):
    return (rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114)[..., None]


def apply_grade(rgb, p):
    """Apply a color grade described by dict p to an [...,3] float image in [0,1]."""
    out = rgb.copy()

    # 1. exposure
    out += p.get("exposure", 0.0)

    # 2. white balance (temp: + warm, tint: + magenta)
    temp = p.get("temp", 0.0)
    tint = p.get("tint", 0.0)
    out[..., 0] *= (1.0 + 0.35 * temp)
    out[..., 2] *= (1.0 - 0.35 * temp)
    out[..., 1] *= (1.0 - 0.22 * tint)
    out = np.clip(out, 0.0, 4.0)

    # 3. gamma (midtone) per channel
    gamma = _as3(p.get("gamma", 1.0))
    out = np.clip(out, 0.0, None) ** (1.0 / np.clip(gamma, 0.05, 10.0))

    # 4. lift + gain (shadows / highlights) per channel
    gain = _as3(p.get("gain", 1.0))
    lift = _as3(p.get("lift", 0.0))
    out = out * gain
    out = out + lift * (1.0 - out)

    # 5. contrast around 0.5
    contrast = p.get("contrast", 1.0)
    out = (out - 0.5) * contrast + 0.5

    # 6. filmic soft shoulder / toe
    filmic = p.get("filmic", 0.0)
    if filmic:
        shouldered = out / (out + 0.18) * 1.18
        out = out * (1 - filmic) + shouldered * filmic

    # 7. monochrome (before saturation)
    if p.get("mono", False):
        l = luma(np.clip(out, 0, 1))
        out = np.repeat(l, 3, axis=-1)

    # 8. saturation + vibrance
    l = luma(out)
    sat = p.get("saturation", 1.0)
    out = l + (out - l) * sat
    vib = p.get("vibrance", 0.0)
    if vib:
        sat_now = np.abs(out - l).mean(axis=-1, keepdims=True)
        out = out + (out - l) * vib * (1.0 - np.clip(sat_now * 3, 0, 1))

    # 9. split toning
    sh_amt = p.get("shadow_amt", 0.0)
    hi_amt = p.get("highlight_amt", 0.0)
    if sh_amt or hi_amt:
        lc = np.clip(luma(out), 0, 1)
        if sh_amt:
            out = out + _as3(p.get("shadow_tint", [0, 0, 0])) * sh_amt * (1.0 - lc)
        if hi_amt:
            out = out + _as3(p.get("highlight_tint", [0, 0, 0])) * hi_amt * lc

    # 10. fade (lift blacks toward a matte floor)
    fade = p.get("fade", 0.0)
    if fade:
        out = out * (1.0 - 0.18 * fade) + 0.18 * fade

    return np.clip(out, 0.0, 1.0)


def to_hex(rgb01):
    r, g, b = (int(round(float(c) * 255)) for c in rgb01)
    return f"#FF{r:02X}{g:02X}{b:02X}"


def swatch(p):
    """Two representative colors for the UI chip: a warm highlight and a cool shadow run through
    the grade, so the chip conveys the look's color character (warmth, saturation, tint)."""
    probe = np.array([[[0.82, 0.62, 0.52], [0.26, 0.32, 0.44]]], dtype=np.float32)
    graded = apply_grade(probe, p)
    return to_hex(graded[0, 0]), to_hex(graded[0, 1])


# -----------------------------------------------------------------------------
# Profiles.  category is one of: camera, lens, film, creative.
# At least half (cameras + lenses) are based on prominent cameras and lenses.
# -----------------------------------------------------------------------------

def P(id, name, category, **params):
    return {"id": id, "name": name, "category": category, "params": params}


CAMERAS = [
    P("fuji_provia", "Fuji Provia", "camera", saturation=1.12, contrast=1.06, temp=0.02),
    P("fuji_velvia", "Fuji Velvia", "camera", saturation=1.5, contrast=1.16, temp=0.05, gain=[1.03,1.0,1.02]),
    P("fuji_astia", "Fuji Astia", "camera", saturation=0.95, contrast=0.96, temp=0.03),
    P("fuji_classic_chrome", "Classic Chrome", "camera", saturation=0.82, contrast=1.12, temp=-0.04, lift=[0.02,0.02,0.03]),
    P("fuji_pro_neg", "Fuji Pro Neg", "camera", saturation=0.9, contrast=0.9, temp=0.02, fade=0.12),
    P("fuji_eterna", "Fuji Eterna", "camera", saturation=0.8, contrast=0.86, fade=0.2, highlight_tint=[0.0,0.05,0.1], highlight_amt=0.3),
    P("fuji_acros", "Fuji Acros", "camera", mono=True, contrast=1.14, gamma=0.95),
    P("canon_standard", "Canon Standard", "camera", saturation=1.1, contrast=1.05, temp=0.04),
    P("canon_portrait", "Canon Portrait", "camera", saturation=1.02, contrast=0.98, temp=0.06, highlight_tint=[0.06,0.02,0.0], highlight_amt=0.25),
    P("nikon_standard", "Nikon Standard", "camera", saturation=1.05, contrast=1.08, temp=-0.02),
    P("nikon_vivid", "Nikon Vivid", "camera", saturation=1.35, contrast=1.14),
    P("sony_standard", "Sony Standard", "camera", saturation=1.04, contrast=1.02, temp=-0.03),
    P("sony_deep", "Sony Deep", "camera", saturation=1.2, contrast=1.12, temp=0.02, gain=[1.0,1.0,1.04]),
    P("leica_standard", "Leica Standard", "camera", saturation=1.02, contrast=1.14, gamma=0.97),
    P("leica_classic", "Leica Classic", "camera", saturation=0.88, contrast=1.1, temp=0.03, fade=0.1),
    P("leica_monochrom", "Leica Monochrom", "camera", mono=True, contrast=1.2, gamma=0.92),
    P("hasselblad_natural", "Hasselblad Natural", "camera", saturation=1.0, contrast=1.03, temp=0.02, gamma=1.02),
    P("ricoh_positive", "Ricoh Positive", "camera", saturation=1.18, contrast=1.12, temp=0.03),
    P("olympus_om", "Olympus OM", "camera", saturation=1.08, contrast=1.06, temp=0.05),
    P("panasonic_lumix", "Lumix Natural", "camera", saturation=1.0, contrast=1.02),
    P("pentax_bright", "Pentax Bright", "camera", saturation=1.22, contrast=1.08, temp=0.04),
    P("sigma_foveon", "Sigma Foveon", "camera", saturation=1.06, contrast=1.18, gamma=0.96),
    P("arri_alexa", "Arri Alexa", "camera", saturation=0.94, contrast=0.9, fade=0.14, highlight_tint=[0.0,0.03,0.06], highlight_amt=0.25),
    P("red_komodo", "RED Komodo", "camera", saturation=1.05, contrast=0.96, temp=-0.03, fade=0.08),
    P("blackmagic_film", "Blackmagic Film", "camera", saturation=0.9, contrast=0.84, fade=0.22),
    P("pixel_hdr", "Pixel HDR+", "camera", saturation=1.14, contrast=1.16, gamma=1.04, vibrance=0.2),
]

LENSES = [
    P("summicron_35", "Summicron 35", "lens", saturation=1.0, contrast=1.16, gamma=0.97, vibrance=0.1),
    P("noctilux_50", "Noctilux 50", "lens", saturation=0.98, contrast=1.08, temp=0.05, fade=0.1, highlight_amt=0.2, highlight_tint=[0.06,0.03,0.0]),
    P("summilux_50", "Summilux 50", "lens", saturation=1.02, contrast=1.12, temp=0.03),
    P("zeiss_planar", "Zeiss Planar", "lens", saturation=1.06, contrast=1.14, temp=-0.02),
    P("zeiss_sonnar", "Zeiss Sonnar", "lens", saturation=1.1, contrast=1.1, temp=0.04),
    P("zeiss_distagon", "Zeiss Distagon", "lens", saturation=1.04, contrast=1.12),
    P("canon_l_85", "Canon L 85", "lens", saturation=1.02, contrast=1.04, temp=0.05, highlight_amt=0.2, highlight_tint=[0.05,0.02,0.0]),
    P("nikkor_105", "Nikkor 105", "lens", saturation=1.05, contrast=1.08, temp=-0.02),
    P("helios_44", "Helios 44", "lens", saturation=1.12, contrast=1.02, temp=0.06, vibrance=0.15, shadow_amt=0.2, shadow_tint=[0.0,0.06,0.02]),
    P("voigtlander_nokton", "Voigtländer Nokton", "lens", saturation=0.98, contrast=1.12, temp=0.02),
    P("sigma_art", "Sigma Art", "lens", saturation=1.08, contrast=1.16, gamma=0.97),
    P("petzval", "Petzval Swirl", "lens", saturation=1.14, contrast=1.06, temp=0.05, vibrance=0.2),
    P("lomo_lens", "Lomo Petzval", "lens", saturation=1.2, contrast=1.1, temp=0.04, shadow_amt=0.25, shadow_tint=[0.0,0.04,0.1]),
    P("cooke_s4", "Cooke S4", "lens", saturation=0.96, contrast=0.98, temp=0.05, fade=0.12, highlight_amt=0.2, highlight_tint=[0.06,0.03,0.0]),
    P("angenieux", "Angénieux", "lens", saturation=0.98, contrast=1.0, fade=0.1, temp=0.03),
    P("trioplan", "Meyer Trioplan", "lens", saturation=1.1, contrast=1.02, temp=0.04, vibrance=0.18),
    P("minolta_rokkor", "Minolta Rokkor", "lens", saturation=1.06, contrast=1.06, temp=0.05),
    P("contax_zeiss", "Contax Zeiss", "lens", saturation=1.08, contrast=1.14, temp=0.03),
    P("takumar", "Takumar 50", "lens", saturation=1.02, contrast=1.04, temp=0.08, highlight_amt=0.2, highlight_tint=[0.08,0.04,0.0]),
    P("jupiter_8", "Jupiter-8", "lens", saturation=0.96, contrast=1.0, temp=0.05, fade=0.14),
    P("nikkor_58_noct", "Nikkor 58 Noct", "lens", saturation=1.0, contrast=1.12, temp=0.03),
    P("leica_apo", "Leica APO", "lens", saturation=1.04, contrast=1.18, gamma=0.96),
    P("fujinon_xf", "Fujinon XF", "lens", saturation=1.08, contrast=1.08, temp=0.02),
    P("laowa_macro", "Laowa Macro", "lens", saturation=1.12, contrast=1.1, vibrance=0.15),
]

FILM = [
    P("kodak_portra_400", "Portra 400", "film", saturation=0.98, contrast=0.94, temp=0.05, fade=0.12, highlight_tint=[0.06,0.03,0.0], highlight_amt=0.3),
    P("kodak_portra_800", "Portra 800", "film", saturation=0.96, contrast=0.96, temp=0.06, fade=0.14, shadow_tint=[0.04,0.0,0.03], shadow_amt=0.2),
    P("kodak_gold_200", "Kodak Gold 200", "film", saturation=1.08, contrast=1.0, temp=0.1, highlight_tint=[0.08,0.05,0.0], highlight_amt=0.35),
    P("kodak_ektar_100", "Ektar 100", "film", saturation=1.28, contrast=1.12, temp=0.03),
    P("kodak_ektachrome", "Ektachrome", "film", saturation=1.16, contrast=1.1, temp=-0.03),
    P("kodak_ultramax", "Kodak UltraMax", "film", saturation=1.12, contrast=1.02, temp=0.08),
    P("kodak_tri_x", "Tri-X 400", "film", mono=True, contrast=1.18, gamma=0.94),
    P("kodak_tmax", "T-Max 100", "film", mono=True, contrast=1.1, gamma=0.98),
    P("fuji_superia", "Fuji Superia", "film", saturation=1.1, contrast=1.02, temp=-0.02, shadow_tint=[0.0,0.06,0.03], shadow_amt=0.2),
    P("fuji_pro_400h", "Pro 400H", "film", saturation=0.95, contrast=0.92, temp=-0.02, fade=0.14, highlight_tint=[0.0,0.05,0.06], highlight_amt=0.28),
    P("fuji_c200", "Fuji C200", "film", saturation=1.05, contrast=1.0, temp=-0.03, shadow_tint=[0.0,0.05,0.02], shadow_amt=0.18),
    P("cinestill_800t", "CineStill 800T", "film", saturation=1.02, contrast=1.0, temp=-0.12, fade=0.1, highlight_tint=[0.1,0.0,0.0], highlight_amt=0.2),
    P("cinestill_50d", "CineStill 50D", "film", saturation=1.06, contrast=1.04, temp=0.02),
    P("agfa_vista", "Agfa Vista", "film", saturation=1.16, contrast=1.06, temp=0.05, shadow_tint=[0.05,0.0,0.02], shadow_amt=0.2),
    P("agfa_apx", "Agfa APX", "film", mono=True, contrast=1.12),
    P("ilford_hp5", "Ilford HP5", "film", mono=True, contrast=1.16, gamma=0.95, fade=0.06),
    P("ilford_delta", "Ilford Delta", "film", mono=True, contrast=1.08),
    P("lomography_400", "Lomography 400", "film", saturation=1.3, contrast=1.1, temp=0.04, vibrance=0.2, shadow_tint=[0.0,0.04,0.08], shadow_amt=0.25),
    P("polaroid_600", "Polaroid 600", "film", saturation=0.9, contrast=0.86, temp=0.06, fade=0.28, highlight_tint=[0.08,0.06,0.0], highlight_amt=0.3),
    P("polaroid_sx70", "Polaroid SX-70", "film", saturation=0.86, contrast=0.84, temp=0.02, fade=0.3, shadow_tint=[0.02,0.0,0.06], shadow_amt=0.25),
    P("instax", "Fuji Instax", "film", saturation=1.0, contrast=0.9, temp=0.05, fade=0.2, highlight_tint=[0.05,0.04,0.0], highlight_amt=0.25),
    P("provia_slide", "Provia Slide", "film", saturation=1.14, contrast=1.12, temp=0.0),
    P("velvia_50", "Velvia 50", "film", saturation=1.55, contrast=1.2, temp=0.05),
    P("expired_film", "Expired Film", "film", saturation=0.8, contrast=0.9, temp=0.08, fade=0.24, shadow_tint=[0.04,0.05,0.0], shadow_amt=0.3),
    P("redscale", "Redscale", "film", saturation=1.1, contrast=1.0, temp=0.5, gain=[1.15,0.9,0.7], highlight_tint=[0.15,0.03,0.0], highlight_amt=0.3),
    P("technicolor_2", "Technicolor", "film", saturation=1.3, contrast=1.18, temp=0.02, gain=[1.05,1.0,1.03]),
]

CREATIVE = [
    P("teal_orange", "Teal & Orange", "creative", saturation=1.12, contrast=1.08, shadow_tint=[0.0,0.05,0.09], shadow_amt=0.35, highlight_tint=[0.1,0.05,0.0], highlight_amt=0.35),
    P("bleach_bypass", "Bleach Bypass", "creative", saturation=0.55, contrast=1.28, fade=0.08),
    P("day_for_night", "Day for Night", "creative", saturation=0.7, contrast=1.1, temp=-0.2, gain=[0.8,0.85,1.05], shadow_tint=[0.0,0.02,0.1], shadow_amt=0.3),
    P("moody_blue", "Moody Blue", "creative", saturation=0.85, contrast=1.06, temp=-0.12, shadow_tint=[0.0,0.02,0.1], shadow_amt=0.3, fade=0.1),
    P("golden_hour", "Golden Hour", "creative", saturation=1.1, contrast=1.02, temp=0.16, highlight_tint=[0.12,0.06,0.0], highlight_amt=0.4),
    P("vintage_fade", "Vintage Fade", "creative", saturation=0.82, contrast=0.9, temp=0.06, fade=0.3),
    P("cross_process", "Cross Process", "creative", saturation=1.2, contrast=1.14, gain=[1.0,1.05,0.95], shadow_tint=[0.0,0.08,0.05], shadow_amt=0.3, highlight_tint=[0.08,0.06,0.0], highlight_amt=0.25),
    P("noir", "Noir", "creative", mono=True, contrast=1.32, gamma=0.9),
    P("sepia", "Sepia", "creative", mono=True, contrast=1.05, highlight_tint=[0.12,0.07,0.0], highlight_amt=0.5, shadow_tint=[0.06,0.03,0.0], shadow_amt=0.3),
    P("infrared", "Infrared", "creative", saturation=1.1, contrast=1.1, gain=[1.1,0.8,1.05], shadow_tint=[0.08,0.0,0.06], shadow_amt=0.3),
    P("cyberpunk", "Cyberpunk", "creative", saturation=1.25, contrast=1.12, temp=-0.08, shadow_tint=[0.06,0.0,0.12], shadow_amt=0.35, highlight_tint=[0.0,0.08,0.1], highlight_amt=0.3),
    P("pastel", "Pastel", "creative", saturation=0.86, contrast=0.9, fade=0.2, highlight_tint=[0.05,0.03,0.05], highlight_amt=0.2),
    P("matte", "Matte", "creative", saturation=0.94, contrast=0.94, fade=0.24),
    P("warm_film", "Warm Film", "creative", saturation=1.02, contrast=1.0, temp=0.12, fade=0.1, highlight_tint=[0.08,0.05,0.0], highlight_amt=0.3),
    P("cold_winter", "Cold Winter", "creative", saturation=0.9, contrast=1.06, temp=-0.14, highlight_tint=[0.0,0.04,0.08], highlight_amt=0.25),
    P("desert", "Desert", "creative", saturation=1.06, contrast=1.04, temp=0.14, gain=[1.05,1.0,0.94]),
    P("forest", "Forest", "creative", saturation=1.1, contrast=1.04, temp=-0.02, gain=[0.98,1.05,0.98], shadow_tint=[0.0,0.06,0.02], shadow_amt=0.25),
    P("neon_nights", "Neon Nights", "creative", saturation=1.3, contrast=1.14, temp=-0.06, shadow_tint=[0.06,0.0,0.12], shadow_amt=0.35, highlight_tint=[0.1,0.0,0.08], highlight_amt=0.3),
    P("vhs", "VHS", "creative", saturation=1.16, contrast=0.94, temp=-0.04, fade=0.2, shadow_tint=[0.0,0.06,0.08], shadow_amt=0.3),
    P("dreamy", "Dreamy", "creative", saturation=0.96, contrast=0.9, fade=0.24, highlight_tint=[0.06,0.04,0.06], highlight_amt=0.3),
    P("high_key", "High Key", "creative", exposure=0.08, saturation=0.96, contrast=0.9, fade=0.1),
    P("low_key", "Low Key", "creative", exposure=-0.06, saturation=1.02, contrast=1.24, gamma=0.9),
    P("mono_contrast", "Mono Contrast", "creative", mono=True, contrast=1.4, gamma=0.86),
    P("faded_noir", "Faded Noir", "creative", mono=True, contrast=1.08, fade=0.22),
]

PROFILES = CAMERAS + LENSES + FILM + CREATIVE


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    base = identity_lut()
    manifest = []
    ids = set()
    cam_lens = 0

    for prof in PROFILES:
        pid = prof["id"]
        assert pid not in ids, f"duplicate id {pid}"
        ids.add(pid)
        if prof["category"] in ("camera", "lens"):
            cam_lens += 1

        params = amplify(prof["params"])
        graded = apply_grade(base, params)
        img = Image.fromarray((graded * 255.0 + 0.5).astype(np.uint8), "RGB")
        fname = f"{pid}.png"
        img.save(os.path.join(OUT_DIR, fname), optimize=True)

        s0, s1 = swatch(params)
        manifest.append({
            "id": pid,
            "name": prof["name"],
            "category": prof["category"],
            "asset": f"luts/{fname}",
            "swatchStart": s0,
            "swatchEnd": s1,
        })

    # "Original" (passthrough) is prepended by the app; the pack is the graded set.
    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump({"version": 1, "count": len(manifest), "luts": manifest}, f, indent=2)

    assert len(manifest) == 100, f"expected 100 LUTs, got {len(manifest)}"
    print(f"Generated {len(manifest)} LUTs  ({cam_lens} camera/lens based)  ->  {OUT_DIR}")


if __name__ == "__main__":
    main()
