# Mari Review Progress

## Published r1094 (2026-10-26)

The current published edition is **r1094**, titled `Элнет пӱнчерыште [r1094]`.
The user explicitly authorized applying the proposed gutter restorations,
including uncertain readings. `gutter-restorations.json` records 59 groups:
58 changed groups and one already matching the selected reading. Six groups
remain low-confidence conjectures, not certified transcriptions of the scan.

`gutter-restoration-results.json` records before/after text and analyzer run IDs.
Every sentence was analyzed separately in Vienna and the persisted runs were
combined with checked Unicode offsets before preparing and validating saves.
45 redundant active records were merged. All 5,164 unrelated r941 documents
were verified unchanged. Existing translations were retained where available.

The complete text, exact deployed annotated JSONL, and its hash manifest are in
`materials/mhr/elnet/`. `publication-verification.json` verifies all 59 groups
through the public reader API across 33 pages, with 50,137 published tokens.
Playwright verified login, edition selection, title page and next-page reading
without JavaScript errors. Test reading events/state writes were intercepted,
so the user's reading statistics and saved location were not modified.
The existing toolbar has horizontal overflow at a 390px viewport; this content
publication does not change reader CSS or claim to fix that separate UI issue.

The 21 Node tests, 21 Python unit tests and PostgreSQL integration test passed.
Old editions and their statistics were not overwritten. Historical ledgers below
describe earlier checkpoints; source uncertainty is not erased by authorization.

## Historical Boundary Review (After Publication r475)

At this historical checkpoint, draft **r540** was not published and had 41 saved
source-checked corrections and 27 consumed false sentence boundaries. All 5,460
untouched records were compared exactly with the r475 snapshot. Changes were
reanalyzed by Vienna and validated before saving. `boundary-corrections.json`
contains the explicit replacements and PDF evidence; `bracket-unresolved.json`
records unresolved source/structure problems (including the first-page
`адакшым.!` case, which must not be advertised as fixed).

All 43 initial unmatched bracket characters were visually inspected: 21 corrected,
22 unresolved in that ledger. The remaining non-bracket windows are NOT fully
visually reviewed. The r540 structural rescan flags 2,162 windows. This is an
incomplete review, not a certified proofread edition. Publication r475 remains live.

The server now supports transactional `merge_sentences` with optimistic revisions,
adjacency checks, dry-run validation, lossless text reconstruction and archived
consumed records. Source references, layouts and historical revisions survive.
Archived records are excluded from draft listings/publication and reject edits.

`editorial/scan_boundaries.py` tests joins across every word-bearing record boundary,
including intervening punctuation-only records, without requiring an unknown edge.
At r498 it tested 4,973 word-bearing boundaries and found 73 HFST hypotheses.
Recognition alone is never sufficient to approve a join.

The join-candidate pass did not validate sentence boundaries or all punctuation.
Use `node tools/editorial-boundaries.mjs .codex/editorial/review/snapshot.json .codex/editorial/review/boundaries.json`
to inventory EVERY adjacent record pair, extending windows through punctuation-only
records. Morphological recognition does not exclude any pair. Source-page changes,
lowercase continuations, suspicious punctuation and unmatched brackets prioritize
visual inspection; they do not authorize automatic corrections. Brackets are
matched across the entire work, not independently within sentence records.

The r475 scan covers 5,527 boundaries of 5,528 records: 2,218 flagged windows,
836 windows extended beyond two records, and 43 unmatched bracket characters.
These counts overlap and are NOT counts of confirmed errors. All windows remain
pending visual review at scan time. The first-page `пассажир.) екий`, `адакшым.!`, and
`Кузе тудо)` cases must be reviewed with surrounding text and the PDF image.
No text is changed or published by this scan. Regression tests:
`node --test tools/editorial-boundaries.test.mjs`.

This is an in-progress editorial review, not a completed correction of the book.
The authoritative edits and their prior revisions live in the server's PostgreSQL
`editorial` schema. Revision 475 is published as a separate reader edition,
`Элнет пӱнчерыште [r475]`, on https://uqureader.tatar/.
`publication.json` records its manifest and checksum. Existing reader assets and
reading statistics have not been overwritten; the new edition has separate statistics.

`mari-progress.json` records individual, visually justified changes, source PDF
page numbers, analysis run IDs, before/after text and recognition counts. Its
`published` flag records publication of the current revision. `complete` remains
false: publication does not imply that all remaining proofreading issues are resolved.

The first batch examines physical PDF pages 4 through 10. Remaining work includes
incorrect sentence boundaries, margin-only fragments on those pages, and visual
review of the rest of the PDF. A page being inspected does not mean all its
sentences have been approved.

The full-work HFST scan is read-only. It enumerates candidate joins and cases in
which HFST recognizes a token unrecognized by the current Vienna annotation.
Candidate recognition alone is never used to approve a transcription change.

## Full Join-Candidate Pass

The revision-64 scan contains 522 candidate pairs, not 522 independently broken
words. The earlier 63 corrected sentence records predate that scan and are not
its completion count. Some pairs overlap within a single letter-spaced word;
others cross genuine word boundaries or contain gutter noise.

All 33 contact sheets (16 candidates each, 10 on the last sheet) were visually
inspected against original PDF graphics. Difficult cases were additionally
checked on full pages. `join-review-notes.json` records that review;
`join-resolutions.json` contains explicit full-word spans and exceptions.
`join-candidates.json` gives one decision for every baseline candidate. An
`unresolved` decision is NOT an approved correction: obscured letters or broken
sentence boundaries must not be guessed from analyzer coverage alone.

`node tools/editorial-join-plan.mjs` builds nonoverlapping, Unicode-offset edits
against the revision-64 snapshot. `apply-joins 0 2` and `apply-joins 1 2` in the
review driver apply two bounded shards. Each sentence is analyzed by Vienna,
validated and saved together, with first-fragment page placement and original
source references. Per-sentence checkpoints make successful saves resumable.
The immutable `snapshot-r64.json` is the baseline, even after a fresh snapshot.
Two additional bounded workers processed the later part of the list. Follow-up
visual refinements are recorded as new revisions, never overwriting history.
`node tools/editorial-join-report.mjs` verifies the final server snapshot against
every planned edit and all 63 earlier corrections before exporting the summary.

The second visual pass additionally tested seven full-word hypotheses through
Vienna (`join-hypotheses.json`). These resolve ten overlapping candidate pairs.
The report separately lists valid transcriptions still unknown to Vienna.

This pass covers the scan's complete candidate list across the imported PDF,
including later essays, contents and colophon. It does not certify every
sentence, all spelling, or all sentence boundaries in the book.

## Continuing

### Boundary Review Checkpoint (2026-10-25)

At this historical checkpoint the draft was revision 941, with reader edition r475.
The boundary pass has 263 saved correction groups, with original source-page
evidence in `boundary-corrections.json`. Every changed group was reanalyzed by
Vienna and validated before saving. One dependent follow-up is recorded in
`boundary-followups.json`. Merges archived 260 redundant records;
5,006 untouched baseline documents were verified structurally equal.
Earlier word-join corrections remain in the revision-475 baseline.

`boundary-unchanged.json` records 38 visually confirmed punctuation windows
which must not be normalized away. It does not certify their entire pages.
The latest scan still contains 44 lowercase-continuation windows and other
flagged boundaries. These are candidates, not a count of proven errors.
Full proofreading is NOT complete, and this draft has NOT been published.

`boundary-pending-r905.json` freezes the 127 priority windows from the start of
this pass. `boundary-priority-audit.json` gives each an evidence-backed outcome:
14 corrected flagged contexts, 35 verified punctuation contexts, 1 partial
correction, and 77 contexts containing source-unreadable fragments. Windows
overlap; these numbers are not counts of distinct spelling errors. Partial
corrections can also occur inside the source-unreadable group.
The priority audit is complete, but text restoration is NOT complete.
`boundary-source-blockers.json` records the specific clipped words and pages.
Better source images are needed to certify the hidden letters and punctuation;
a successful morphological analysis alone must not choose those spellings.

`boundary-pending.json` exports the 106 current priority scanner windows,
with dispositions and evidence instead of resetting all of them to unreviewed.
There are no unreviewed priority contexts in this scan. That does not certify
unflagged text: the scanner is not a complete spelling-error detector.
A reviewed punctuation sequence does not certify other words in the same
window. The current scan has 14 unmatched
brackets; the historical 43-character bracket audit is a different inventory.
Five newly exposed bracket artifacts were corrected on pages 42, 67, 92, 119
and 127. Removing earlier artifacts can expose previously spurious long-range
bracket matches, so a fresh scan is required after every batch.

The same-text merge bug is fixed: a survivor matching its target no longer
proves that trailing noise was archived. Records 456/457 were actually merged.
The report now verifies all consumed records are absent from the active book.
Chapter 5 was restored with heading metadata. On p103, a list of author names
incorrectly classified as footnote 103.1 was returned to its original paragraph;
the interrupted cross-page word произведенийышт was joined. These edits retain
explicit uncertainty for other clipped letters in the affected paragraphs.
The p123 follow-up merges 4740/4741 against r905 and preserves the earlier
parenthesis removal. The report verifies the complete correction chain.

The standalone date 1980 on page 129 was restored from a spurious `то`, not
deleted. Vienna rejected the digits-only request before any save; the date was
successfully analyzed with the preceding paragraph and kept on a separate line.
The failed request checkpoint is retained outside the successful-results folder.

Continue from `.codex/editorial/review/boundaries-current.json`, using original
page graphics. Append disjoint evidence-backed correction groups, run
`node tools/editorial-review.mjs boundary-apply`, refresh `snapshot`, then run
`node tools/editorial-boundary-report.mjs`. The report checks every planned save,
rejects overlapping correction groups, compares all untouched documents and
rescans the whole current book. Do not use a later snapshot as the correction
baseline: `snapshot-r475.json` remains immutable.
Dependent follow-ups use the separately frozen `snapshot-r905.json` and
`node tools/editorial-review.mjs boundary-followup`; never rebase them silently.
Run the four Node test files for boundaries, token edits, boundary state and
priority audit. All 19 tests passed at r941. Publication remains unchanged.

- Local checkpoints, page images and complete analysis responses are under the
  ignored `.codex/editorial/review/` directory. The server retains analysis runs.
- `node tools/editorial-review.mjs snapshot` refreshes sentence revisions and the
  complete inventory; `baseline-snapshot.json` retains the initial snapshot.
- `node tools/editorial-review.mjs render 11 12` retrieves original page images.
- `node tools/editorial-review.mjs analyze-unknown 0 2 20` reanalyzes a bounded
  shard of suspect sentences. The second shard uses `1 2 20`. Never use excessive
  concurrency against the external Vienna service. Errors are stored separately.
- `apply-reviewed FILE.json` accepts a manually reviewed list of replacements
  with source pages and evidence. It compares the baseline and candidate through
  Vienna, prepares the revision, validates it, then saves with optimistic locking.
  It does not choose a spelling automatically from analyzer coverage.
- `report` exports the current progress ledger into this directory.
- `python admin.py scan_work.py WORK_ID` performs the whole-work context-free HFST
  scan on the server without modifying text. Results remain in `editor-data`.

The MCP analyzer previously mistook Vienna `na` for unknown. It is a proper-name
tag, and is now preserved. `***` is the unknown marker. Regression tests cover both.
