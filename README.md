# Picture Perfect X

A native, real-time filtered camera app in the spirit of Retrica. Point the
camera, scrub through film-style looks, and tap the shutter to save a
full-resolution filtered photo to your gallery.

## Install the latest build

**[picture-perfect-x-debug.apk](https://github.com/cryptowall90/pa/releases/download/debug-latest/picture-perfect-x-debug.apk)**
— tap it on the phone. No sign-in, no zip.

Rebuilt by CI on every push, so the link always serves the current code. It is a
**debug** build: signed with the standard debug key, and its application id ends
in `.debug`, so it installs *alongside* any other copy rather than replacing it.
You will need to allow installs from your browser the first time.

This repository is structured for **two native apps that share a product, not
code**:

```
picture-perfect-x/
├── android/        ← Native Android (Kotlin + Jetpack Compose)   ← shipping first
└── ios/            ← Native iOS (Swift + SwiftUI)                ← planned
```

Android is the first target and is implemented here. iOS will live in `ios/`
(SwiftUI + AVFoundation + Metal/CoreImage LUTs) mirroring the same filter
catalog and capture flow.

---

## Android app

Fully native Kotlin. No cross-platform runtime.

| Concern            | Choice                                                      |
| ------------------ | ---------------------------------------------------------- |
| UI                 | Jetpack Compose (Material 3), single-activity              |
| Camera             | CameraX (`Preview` + `CameraEffect` + `ImageCapture`)      |
| Real-time filters  | GPU `SurfaceProcessor` (OpenGL ES) LUT shader on the preview |
| Still filtering    | GPUImage (`jp.co.cyberagent.android:gpuimage`), offscreen  |
| Filter definitions | Declarative catalog → 512×512 lookup tables                |
| Persistence        | Room (captured-photo index) + MediaStore (the images)      |
| Min / target SDK   | 24 / 35                                                     |

### The four build steps, mapped to code

1. **Camera setup** — `ui/camera/CameraPreview.kt` binds a hardware CameraX
   `PreviewView` to the Compose lifecycle for a live back/front preview.
2. **Filter engine** — the preview is filtered entirely on the GPU:
   `camera/LutSurfaceProcessor.kt` is a CameraX `SurfaceProcessor` that samples
   the camera's OES texture and applies the active LUT in a fragment shader (no
   per-frame CPU copy), attached via a `CameraEffect` in
   `camera/CameraController.kt`. This keeps the filtered viewfinder stock-camera
   smooth and instant.
3. **Filter selector UI** — `ui/components/FilterCarousel.kt` is the bottom
   `LazyRow` of lightweight chips (color + lens glyph + name) for the 100-look
   pack. Tapping one swaps the active GPU LUT instantly.
4. **Capture & save** — the shutter in `ui/components/CameraControls.kt` takes a
   full-resolution still, renders it through the **same** LUT on a detached
   GPUImage instance, and `capture/PhotoSaver.kt` writes it to
   `DCIM/PicturePerfectX` via MediaStore (so shots appear in the phone gallery
   alongside camera photos).

### RAW capture (JPEG / RAW)

- A **format chip** in the camera top bar switches between `JPEG` and `RAW`, appearing only on
  lenses that can actually produce a DNG (checked per-lens via
  `ImageCapture.getImageCaptureCapabilities`).
- **RAW is never filtered.** A DNG is unprocessed sensor data by definition, so looks are baked
  into JPEGs only. RAW saves the `.dng` alone to the phone gallery.
- DNGs are written by CameraX straight into `DCIM/PicturePerfectX` alongside the JPEGs, so they
  show up in the phone's gallery and import into desktop RAW tools.
- **RAW-only shoots without the preview filter.** The LUT never reaches a DNG, so attaching it would
  be a viewfinder that lies about the file — and dropping that GPU stream is also what lets a
  full-size RAW stream bind on cameras that otherwise refuse it. In RAW-only the filter strip and the
  brightness/contrast/saturation sliders are shown disabled, with a dismissible note explaining why.
  **Exposure stays live**, because the sensor applies it and it genuinely changes the DNG.
- **A refused format can never freeze the viewfinder.** A camera that advertises a format can still
  reject the resulting stream combination; the app falls back to JPEG, rebinds, and shows a message
  that stays until acknowledged. The format remains selectable so it can be retried. RAW also uses
  `CAPTURE_MODE_MAXIMIZE_QUALITY`, since low-latency capture opts into zero-shutter-lag paths that
  conflict with RAW on many devices.
- DNG entries carry a **`RAW` badge**.
- **RAW shots stay sharp in the app.** Android's `DngCreator` caps a DNG's embedded preview at
  **256 px**, and phones that can't demosaic RAW have nothing better to show — so every RAW capture
  keeps a full-resolution, **unfiltered** JPEG in app-private storage (`capture/ProxyStore.kt`).
  It never appears in the phone's gallery, the DNG is untouched, and it's what the grid, viewer and
  both editors actually draw — which is also what lets a RAW edit save at full resolution.
  It's deleted with its gallery entry. To obtain that file the shot rides the camera's RAW+JPEG
  stream where one exists, routing the JPEG half to private storage rather than the gallery — the
  user still gets only a `.dng`. Where a lens can't do RAW+JPEG, RAW still works, just without a
  proxy.
- **RAW photos can be edited.** Android's Java decoders don't guarantee DNG support, so the editor
  attempts a full decode and falls back to the embedded preview, telling you when that means the
  saved photo will be lower resolution. Edits always save as a **new JPEG** — a DNG is never
  written to.

### LUT pack + intensity (100 looks)

- **100 real 512×512 GPUImage LUTs** shipped in `app/src/main/assets/luts/`,
  generated by [`tools/generate_luts.py`](android/tools/generate_luts.py) and
  described by `manifest.json` (`id`, name, category, asset, chip swatches).
  `FilterCatalog` loads the manifest at startup.
- **≥50 are based on prominent cameras and lenses** (Fuji film sims, Leica,
  Canon, Nikon, Sony, Hasselblad, Alexa; Summicron, Noctilux, Zeiss, Helios,
  Cooke…), plus film stocks (Portra, Ektar, Tri-X, CineStill…) and creative looks.
  These are parametric emulations *inspired by* the gear — not official profiles.
- **0-100 intensity slider** (`ui/components/IntensitySlider.kt`) drives the LUT
  blend live — the preview shader's `uIntensity` uniform and the capture LUT — so
  the viewfinder updates as you drag; it's hidden for **Original**.
- **Lightweight filter chips** — each look is a small color tile with a lens
  glyph and its name, so the strip and the preview stay smooth across 100 looks.
  (The camera image lives only in the main viewfinder.)

### In-app gallery (`ui/gallery/`)

- A 3-column grid of every capture, read from the local **Room** index; tap the
  library button (bottom-left of the camera) to open it, tap a photo for a
  full-screen viewer, and **swipe left/right to move between photos** without
  going back to the grid.
- **Multi-select delete**: long-press a photo to start selecting, tap more to add,
  then delete — with a confirmation dialog. Delete removes the photo from the
  **app** gallery (its Room index row) only; the file stays on the device, so it
  remains in the phone gallery.
- Images load from their **MediaStore** content URIs via Coil.

### Perfect Editor (`ui/perfect/`)

The heavier editing surface, opened with the crop icon in the photo viewer. A **layered,
non-destructive** editor: every layer is a *description* of a change, never pixels, which is what
makes undo a stack of snapshots, the preview and the export agree by construction, and a saved edit
reopenable months later.

#### Layers

- A **stack** of effects, each with its own opacity, blend mode, visibility and area: Tone, Look
  (any of the 100 filters), Curves, Text, Shape, Smooth, Heal, Whiten, Brighten, Gradient and Fill.
- The **layer sheet** reorders, duplicates, hides, cycles blend modes and deletes.
- **Undo covers everything** — layers *and* framing, so the arrow takes a crop back off.
- **Drafts**: stop mid-edit and pick it up later, without exporting anything.

#### One thing at a time

The panel under the photo shows what you are doing and nothing else. Adjusting an effect is
**three rows**: `+` and its controls and `Layers · n`; the one control; and where it applies plus
the way out. Choosing *where* an effect goes is a different errand, so it is a panel of its own,
entered from **Area** and left with **Done**.

A Tone layer's nine adjustments collapse into **Light** and **Color**, opening in place.

Chips say what they do by how they look: **solid** is what you are looking at, **outlined** is a
button, and a **tinted outline** is a setting that is on.

#### Choosing an area

- Four tools: **Lasso** (draw round it), **Brush** (paint it), **Fade** (a gradient across the
  frame, in five shapes) and **Wand** (tap a colour). Each combines with what you have by
  **New / Add / Subtract**.
- A drawn area is a **floating selection** belonging to the document, not to any layer — so drawing
  a second one can never overwrite the first layer's work. **Apply to \<layer\>** hands it over;
  **+ Effect** gives it to a new one.
- **Points**: a brushed, wand-picked or faded area has no shape, only coverage. This traces its
  boundary (`layers/MaskTrace.kt`, marching squares chained into rings) into a couple of dozen
  draggable handles, so it can be adjusted rather than repainted.
- **Feather**, **Invert** and **Deselect / Clear** act on whichever area is on screen.
- **Pinch to zoom** with a magnifier under the finger for precision.

#### Seeing what you're doing

- Nothing ever covers the photo: every control sits below it.
- **Press and hold** the photo to see it *before* the edit; let go to come back.
- The **eye** shows the edit *without* any outline, handle or dot — the photo exactly as it would
  save — and one more tap returns to editing.

#### Geometry

- **Crop** with a draggable frame — corner and edge handles, rule-of-thirds grid, dimmed surround.
- **Aspect ratios**: Original, Free, 1:1, 4:5, 9:16, 16:9, 3:2, 4:3, 5:7. With a ratio locked,
  corners resize proportionally and the frame can be dragged around.
- **Straighten** (−45°…45°), **rotate** in quarter turns, and **flip** both ways. Straightening
  scales just enough to cover the frame, so a levelled photo never shows empty corners.
- The framing is a declarative `ImageGeometry` (flips → turns → straighten → crop) with the crop
  held in **normalized 0..1 coordinates**, so the preview and the full-resolution export frame
  identically by construction. The maths lives in `capture/ImageGeometry.kt`, free of Android types
  and **covered by unit tests**.
- Export orients, **then** composites the stack, **then** crops — the same order the preview uses,
  and the only order that can be right: a mask means "this fraction of the frame", so cropping first
  would land every mask, caption and gradient somewhere the preview never showed.
- Saves as a **new photo**; the source is never modified. The stack is written beside it, so the
  edit can be reopened and revised rather than being the last thing that happens to that photo.

> **Known limitation:** a mask is normalized against the *oriented* frame, and rotating, flipping or
> straightening re-orients the photo without moving the masks with it. Frame the photo before
> masking it; doing it the other way round leaves the effect where the frame used to be.

#### Adjustments belong to the area, not to one effect

**Exposure, contrast, blacks, shadows, highlights, whites, saturation, vibrance and warmth**, each
−100…100 — and carried by **every layer that changes the photo**, not just an adjustment layer. So a
lassoed area with a filter in it can also be brightened, in place, without a second layer given the
same area by hand. Text, shapes and gradients are left out: they draw their own content and have a
colour wheel already.

A **drawn area with nothing in it yet** shows them too. Moving a slider is what creates the layer,
carrying that area — so you never have to know which effect happens to be the one that does
exposure.

It is free when untouched: `ToneAdjustments.isNeutral` and `capture/ImageToner.kt`'s early return
mean a layer nobody has adjusted takes no GPU pass and allocates no bitmap, and defaults aren't
serialized, so it adds nothing to a saved edit either.

GPUImage's own highlight/shadow filter only lightens shadows and only darkens highlights, so
`capture/GPUImageToneFilter.kt` is a custom one-pass shader that weights each adjustment by where a
pixel sits in the luminance range — overlapping bands, so the controls blend rather than band at
their edges.

#### What CI can actually check

`dl.google.com` is blocked in the sandbox this is developed in, so nothing here compiles locally and
CI is the only build. That shapes the code: the geometry, the masks (lasso, brush, wand, gradient,
outline tracing, combination), the curves, the colour conversion, the run-length codec, the layer
stack and its undo history are all **pure Kotlin with no Android types**, and all covered by unit
tests. The Compose surface on top is the part that has to be judged on a phone.

### Light editor (`ui/edit/`)

- Re-edit a saved photo (Edit in the viewer) or **import a device photo** (the
  system photo picker — no extra permission). Either way a named chooser asks for
  **Light Edit** or **Perfect Editor**, so both surfaces are discoverable by name.
- Same looks + intensity + **exposure / brightness / contrast / saturation** as the
  camera, rendered live via `capture/ImageEditor.kt` (GPUImage off-screen).
- **Press and hold** the preview to compare against the unedited original.
- **Saved as a new photo** — the original is never modified. All on-device.

### Everything is on-device — zero server cost

The app declares **no `INTERNET` permission** and has no backend. Filtering runs
on the GPU (GPUImage), thumbnail previews are computed on the CPU, photos are
written to the device's shared storage (MediaStore), and their index lives in a
local SQLite database (Room). Nothing is uploaded, so there is nothing to host
and no per-user server bill.

### Extras added beyond the four steps

- **Runtime camera-permission flow** with rationale screen (`ui/PermissionGate.kt`).
- **Front/back lens switch** and **flash mode cycle** (off → on → auto).
- **Last-photo thumbnail** in the control bar.
- **Room "schema"** indexing every capture (uri, filter, lens, dimensions,
  timestamp) — backs the in-app gallery.
- **Unit tests** validating the LUT pack and a **GitHub Actions** workflow that
  builds the APK and runs tests.

### Data schema (Room)

`data/PhotoEntity.kt` — table `photos`:

| column       | type   | notes                                   |
| ------------ | ------ | --------------------------------------- |
| id           | Long   | PK, autogenerated                       |
| uri          | String | MediaStore content URI of the JPEG      |
| displayName  | String | e.g. `PPX_1721990000000.jpg`            |
| filterId     | String | stable id from the filter catalog       |
| filterName   | String | display name at capture time            |
| lensFacing   | String | `front` / `back`                        |
| width/height | Int    | pixels of the saved image               |
| createdAt    | Long   | epoch millis                            |

The schema is exported to `android/app/schemas/` at build time (Room
`exportSchema = true`) for migration tracking.

### Adding / editing looks

Add a profile to the tables in `android/tools/generate_luts.py` and re-run it
(`pip install numpy Pillow && python3 android/tools/generate_luts.py`) — it
rewrites the PNGs and `manifest.json`, which the app reads at startup. To
hand-author a look, drop a real 512×512 lookup PNG in `assets/luts/` and add a
manifest entry pointing at it.

---

## Building

Requires the Android SDK and access to Google's Maven repository
(`dl.google.com`).

```bash
cd android
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run unit tests
./gradlew installDebug         # install on a connected device/emulator
```

The APK lands in `android/app/build/outputs/apk/debug/`.

> **Note on this development environment:** the sandbox this was scaffolded in
> blocks `dl.google.com` by egress policy, so AGP/AndroidX can't be fetched to
> run a full compile here. The Gradle wrapper, build scripts, and version
> catalog are validated, and every GPUImage API call was checked against the
> published `gpuimage:2.1.0` sources. The included CI workflow performs the full
> build on GitHub-hosted runners, which have both the SDK and Google Maven.

## Roadmap

- Live per-thumbnail camera preview in the filter carousel.
- In-app gallery backed by the Room index.
- Real LUT pack (Fujifilm/Leica/Polaroid emulations).
- Filter intensity slider and exposure/tap-to-focus.
- iOS app under `ios/` sharing the same filter catalog and capture flow.
