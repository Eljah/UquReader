# Yazgy Jillar: Source Mapping and Editorial Draft

## Source Selection

The local OCR project is `D:/Java/kazan-utlary-ocr-java`. Its existing
`out/issue/images/0001.jpg` through `0138.jpg` belong to issue 1948-8.
They contain a different installment, beginning with chapter XIV, and must
not be attached to the current reader installment beginning with chapter XXV.

The correct source is Soviet Adabiyaty, issue 1948-10, printed pages 37-73:
https://kazanutlary.ru/j-archive/journals/1948-10

`source-mapping.json` records the 37 archive URLs, image SHA-256 hashes,
editorial source IDs and the stable IDs of all 809 active text units.
Each source is a standalone image, so MCP `sourceRefs.page` is **1**;
`archiveImage` identifies the printed page. They are not interchangeable.

## Completed Work

Current published text: **revision 1298**, exported in `yazgy-draft-r1298.jsonl` and
`yazgy-draft-r1298.txt`. User-confirmed readings on pages 66 and 67 were applied
after revision 1296. Publication ID: `edition_fea266e5c60a4747898024bd321321ca_r1298`.
The public API was checked against all 20,732 exported tokens; see
`docs/editorial-review/yazgy-publication-verification.json`.
The full first visual pass covers all 37 printed pages
(37-73), chapters XXV-XXXI, of this installment, not the entire novel.
All 801 active text units have review records. There are 20,732 tokens,
including 13 explicitly marked footnote tokens and seven chapter headings.
The review logs contain 463 analyzer-attested saves across 440 stable IDs.
Reversed columns on pages 42 and 62 were reordered with audited structural
operations; split words and adjacent-unit sentence boundaries were repaired.
The final targeted pass corrected another 22 units. Earlier snapshots below
are historical checkpoints, not the current text.

See `docs/editorial-review/yazgy-final-audit.json` for coverage and integrity,
and `yazgy-final-server-validation.json` for server-side validation results.

- Attached all 37 images to the server-side editorial work.
- Mapped all 809 units; 36 boundary units retain both adjacent images.
- Corrected two opening passages against the image, merging the split first
  sentence and obtaining new attested Tatar analyses before saving.
- Exported revision 813 as JSONL (complete records and analyses) and plain text.
- Verified every saved source reference and every text/token hash after mapping.
- Fixed MCP image rendering to preserve native pixel dimensions despite print DPI.

Second pass: revision 820 additionally corrects seven units on page 37 and its
continuation onto page 38. The bibliographic footnote has its complete text and
explicit footnote token roles. All other 802 units, source references and stable
ordinals are unchanged. See `docs/editorial-review/yazgy-page37-pass2-results.json`
and its verification report. Revision 813 and its mapping hashes remain the
baseline; revision 820 is the first proofreading checkpoint.

Revision 828 adds eight corrected units on page 38 (ordinals
19-21, 23-24, 26-28), including commands, names and the verse insertion. The
other 801 units are unchanged from revision 820. See the `yazgy-page38-pass1`
plan, results and verification in `docs/editorial-review/`.
Revision 845 finishes the text pass through page 39 and its
continuation onto page 40. Seventeen units were corrected; twelve other units
on page 39 were visually checked without changes. Stable IDs, ordinals and
source references are unchanged. Adjacent-unit sentence repairs retain matching
dictionary translations, with 65 translation-preservation assertions passing.
See `docs/editorial-review/yazgy-pages38-39-pass2-verification.json` and the
corresponding plan/results for the complete audit trail.

Historical checkpoint: revision 860 completes the text pass through page 40 and the
opening sentence of page 41. Fifteen units were corrected and nine were checked
without changes. The other 794 units are unchanged from revision 845; all
stable IDs, ordinals and source references remain unchanged. See the
`yazgy-page40-pass1` plan, results and verification report. The legacy
punctuation-only units 72/73 still complete the ellipsis after unit 71; they
have not been deleted or reassigned.

Pages 41-73 and both column-order repairs are now complete. This is not final
publication QA: footnote placement, exact pagination and typography still need
a reader-side check before publication.

Earlier snapshots are editorial drafts. Revision 1298 is published as a new,
immutable reader edition. Existing books and reader statistics were not replaced.
The original import's legacy `sourceStatus: missing-pdf` metadata is not a
source inventory; use actual source records and this manifest for image coverage.

## Remaining Review

Page mapping is provenance coverage, not completed proofreading or exact token
pagination. OCR alignment was rejected for insufficient matching coverage.
Visual boundary review and surviving printed-page markers were used instead.
The original OCR reversed columns on pages 42 and 62; both blocks have now
been reordered against the complete images. The page
68 OCR marker was misread as 65; the image establishes the correct source.

Words, dialogue marks and sentence boundaries were reviewed throughout this
installment. The third proposed opening correction was
resolved visually in the second pass (printed `җирендәрәк`); the original proposal
is retained only as historical evidence, not as a plan to apply again.

Residual small-print ambiguities are recorded in
`docs/editorial-review/yazgy-residual-readings.json`. No speculative replacements
were applied to the one unresolved reading on page 61. Two other readings were
resolved by the user. The published edition retains automatic reader pagination;
physical page matching and footnote placement remain separate layout work.
Historical reader statistics have not been migrated or replaced.

## Reproducibility

- `tools/yazgy-source-download.mjs`: resumable, hash-recorded archive download.
- `tools/yazgy-editor.mjs`: snapshot, upload, render, corrections and mapping via MCP.
- `tools/yazgy-map-reviewed.mjs`: builds reviewed boundaries from the revision-4
  snapshot only; intentionally rejects a later snapshot to prevent ordinal drift.
- `tools/yazgy-review-report.mjs`: verifies saved text/tokens/source references and
  exports the draft and `docs/editorial-review/yazgy-progress.json`.

`editorial/align_source_images.py` is a fail-closed OCR alignment helper, not the
method accepted for this edition. Fresh OCR outputs are diagnostic material only.
