# Elnet Reader Edition r1094

Published on 2026-10-26 as `edition_43be7044888e4e938575110b818ca1d2_r1094`.

- `elnet-r1094.txt` is the complete reconstructed text, including earlier repairs.
- The JSONL is the exact deployed, annotated reader asset, not a new OCR pass.
- Its companion metadata contains the publication SHA256, language and work ID.
- `.gitattributes` preserves the JSONL bytes so checkout cannot invalidate the hash.

The 59 reviewed groups include 58 changed groups and one already matching the
preferred reading. Six groups are explicitly low-confidence conjectures.
All were authorized by the user; this does not make hidden source letters certain.
See `docs/editorial-review/gutter-restorations.json` for selected replacements and
`gutter-restoration-results.json` for before/after text, analysis runs and history.

To restore this publication, copy the JSONL into `EDITOR_PUBLISH_DIR` first,
verify its SHA256, then install the metadata file last. The reader watches that
directory. Do not overwrite the original Vienna asset or a previous edition:
their token indices are referenced by reading statistics.

This edition does not certify every spelling in the book. Remaining unknown
analyses outside the requested restoration list are not silently replaced.
