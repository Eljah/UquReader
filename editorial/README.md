# UquReader editorial MCP

Private, server-side MCP tools for preparing and revising reader materials. This is
an editing API, not a public reader API. PostgreSQL is required; there is no SQLite
fallback. The protocol uses the official MCP Python SDK over SSH/stdio.

## Deployment and connection

- Python 3.11+, PostgreSQL, and the dependencies in `requirements.txt` are required.
- `deploy.sh` installs the application in `/opt/uqureader/editor`, its virtualenv,
  source storage and publication directory. Run as root after staging the files.
- The service reads the existing protected `/etc/uqureader-web.env` through
  `admin.py`; secrets are not stored in repository configuration.
- `DATABASE_URL` must be a PostgreSQL URL (a leading `jdbc:` is accepted).
  `EDITOR_DATABASE_URL` can override it. `EDITOR_DATA_DIR` holds source binaries.
- `EDITOR_PUBLISH_DIR` must point to the same directory for the MCP process and
  Java reader. The reader checks completed manifests in a background thread every
  five seconds. A restart is needed only when first enabling this integration.
- A dedicated SSH key is restricted to `/usr/local/bin/uqureader-editor`, with
  forwarding and PTY disabled. No anonymous HTTP editing endpoint is exposed.
- The local, ignored `.codex/config.toml` registers `uqureader_editor`. Start a new
  Codex session to load newly registered tools. `codex mcp list` checks discovery;
  `node tools/editorial-smoke.mjs` tests the actual deployed protocol.
- Back up the `editorial` PostgreSQL schema **and** `editor-data` together. Back up
  published JSONL files and their metadata manifests. Source IDs are immutable.
- PyMuPDF is AGPL/commercial licensed; review its terms before redistributing or
  changing the deployment model. Its implementation is not bundled into Java.

## Data contract

Works have a stable UUID, explicit language (`mhr` or `tt`) and revision. Each
sentence/heading/footnote unit has a stable ID, ordinal and revision. Segmentation
of legacy materials is provisional, not a claim that punctuation was corrected.

Documents contain `text`, `tokens`, `trailing`, `sourceRefs`, and optionally `kind`.
Each token has `prefix`, `surface`, `start`, `end`, `analysis`, `analyses[]`, and
optional translations/layout (`pageIndex`, `sourcePage`, `role`, `footnoteId`).
Offsets are Unicode code points, not JavaScript UTF-16 units. Concatenating every
prefix/surface plus trailing whitespace must reproduce text exactly.

`sourceRefs: [{"sourceId": "...", "page": 4}]` always means physical, **1-based**
PDF pages. Image sources have one page. Reader `pageIndex` remains zero-based;
legacy `sourcePage` is a printed folio and is not used to locate PDF images.
A sentence spanning pages has multiple source references. References can be
updated using `save_sentence` without a new analyzer run if text/analyses are
unchanged. Cropped regions are not currently stored: tools return full pages.

## Agent workflow

1. `list_works`, then `list_sentences` with pagination and `unknown_only=true`.
2. `get_sentence` or `find_sentence_sources(work_id, text)`. Ambiguous textual
   matches return all candidates; select by ID, not by guessing the first match.
3. `render_source_page` for every referenced page. Read the actual image; the
   embedded machine-readable text remains the base, not a replacement OCR pass.
4. `list_analyzers(language)` returns counts, IDs and availability. Mari supports
   `vienna` and `giella-hfst`; Tatar uses `uqureader-tatar` via the reader backend.
5. `analyze_sentence` or `compare_hypotheses`, explicitly selecting the analyzer.
   Send one sentence at a time. All alternative parses are kept. Successful runs
   are persisted/cached; `refresh=true` explicitly requests reanalysis. Transport
   errors and alignment failures are errors, never fabricated unknown results.
6. Compare coverage **and** visual evidence. Higher analyzer coverage alone is
   not a reason to change a spelling. Unknown tokens may be genuine words.
7. `prepare_sentence_revision` creates a candidate from the attested run, carrying
   source references, matched layout and unchanged translations forward. Review
   layout warnings, especially when words join across page/footnote boundaries.
8. `validate_sentence`, then `save_sentence`, supplying `expected_revision`,
   candidate document, `run_id` and a reason describing the visual evidence.
   Both use the same validator; saving reruns validation under database locks.
9. `sentence_history` and `get_sentence_revision` expose the audit trail.
   `split_sentence` splits an incorrectly joined unit without changing text or
   analyses, rebases offsets and preserves references/history. Review the shared
   sourceRefs after splitting if each half needs a narrower page mapping.
10. `publish_work(work_id, expected_revision)` creates a new immutable edition.

For a record containing multiple sentences, analyze each sentence separately,
preserving its whitespace, then use `combine_analysis_runs(language, run_ids)`.
This combines only server-persisted runs of the same analyzer/language, rebases
Unicode offsets, and produces one attested run for `prepare_sentence_revision`.
It neither invents morphology nor changes book text. Source restoration can be
explicitly authorized as conjectural; preserve that distinction in save reasons.

The validator rejects broken token reconstruction, wrong offsets, lost diacritics,
invalid source ownership/page bounds, malformed analyses and stale writes.
Morphology must match a server-recorded run or the unchanged original revision.
It does **not** prove that a plausible transcription matches the printed page;
that remains the visual review step. Metadata correction is a trusted editor action.

## Creating a work

Use `create_work`, then `upload_source` with PDF/PNG/JPEG bytes encoded as base64,
filename and SHA256. Maximum binary source size is 32 MiB; larger sources need
splitting into PDFs or individual page images. Source binaries are not accepted
via arbitrary server paths or fetched URLs. This avoids path traversal and SSRF.

Analyze each sentence, add its sourceRefs and layout to the resulting document,
then `append_sentences(work_id, documents, run_ids)` in batches up to 100. A batch
is atomic. Headings and footnotes can be separate units. Finally publish explicitly.

Published IDs differ from the original book ID because existing reader statistics
refer to ordinal token offsets. Overwriting a book in place would silently move
historical events to different words. Old books/events stay intact; cross-edition
statistics migration is intentionally not automatic. The publication manifest
contains title, language, revision, work ID and a SHA256 checksum.

## Existing books

`import_existing.py` is an administrative, lossless bootstrap for already prepared
JSONL. Existing annotations and dictionary translations are retained. Legacy
non-whitespace prefixes become explicit tokens; empty `Error` placeholders with
no text are omitted. Neither transformation changes the reconstructed text.

The Mari PDF is matched against the **same embedded-text extraction** used to
build the current book. Page matching is monotonic, content-based, with a minimum
overlap threshold; it never substitutes printed folios for PDF indices. These
bindings are provenance for visual review, not proof of OCR correctness.
`audit_imports.py` verifies full reconstructed-text equality and every reference.

The current Mari import has 5,528 provisional units / 51,575 tokens, mapped to 142
PDF pages. The current Yazgy import has 810 units / 17,891 tokens. Its source PDF
has not been supplied/found: `sourceStatus=missing-pdf`, and sourceRefs stay empty
rather than inventing page numbers. Attach the original PDF before visual review.

## Verification

```sh
python -m unittest -v
python admin.py integration_test.py
python admin.py audit_imports.py
```

The integration test uses a uniquely identified fixture, validates source upload,
PNG rendering, append/save, stale-write rejection, history, lossless splitting
and immutable publication in a temporary directory, then removes only its own
fixture records. It does not reset user reading statistics.

Java tests: `mvn -f web-app/pom.xml test`. The reader tests cover explicit language,
idempotent background publication loading and rejection of checksum mismatches.
