# LUT lookup tables

Drop 512×512 lookup-table PNGs here to upgrade any filter from its parametric
fallback to a true 3D LUT. The filename must match the `lutAsset` in
`FilterCatalog.kt`, e.g.:

- `fujifilm.png`
- `leica.png`
- `polaroid.png`

A LUT PNG is the standard 512×512 "neutral" Hald/lookup image edited in your
grading tool of choice. When a matching file is present, `FilterFactory` loads it
via `GPUImageLookupFilter`; when absent, it falls back to the parametric look so
the app always builds and runs with distinct filters.
