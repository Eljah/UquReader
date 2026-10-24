"""Private MCP stdio endpoint. Never expose this editing interface anonymously."""
from mcp.server.fastmcp import FastMCP, Image

from analyzers import available
from store import Store

mcp = FastMCP('UquReader Editorial')
store = Store()


@mcp.tool()
def list_works() -> dict:
    """List editorial works and their current revision numbers."""
    return {'works': store.works()}


@mcp.tool()
def create_work(title: str, language: str, metadata: dict | None = None) -> dict:
    """Create a draft (language tt or mhr). Does not publish to readers."""
    return store.create(title, language, metadata)


@mcp.tool()
def list_sentences(work_id: str, offset: int = 0, limit: int = 25, unknown_only: bool = False) -> dict:
    """Page through stable sentence records, optionally only unknown morphology."""
    return {'sentences': store.sentences(work_id, offset, limit, unknown_only)}


@mcp.tool()
def get_sentence(sentence_id: str) -> dict:
    """Read text, tokenization, annotations, sourceRefs and optimistic revision."""
    return store.get(sentence_id)


@mcp.tool()
def find_sentence_sources(work_id: str, text: str) -> dict:
    """Find exact sentence text, falling back to a literal substring. Return all matches;
    never silently select an ambiguous sentence. sourceRefs pages are physical, 1-based.
    Use render_source_page to see the actual page, not OCR.
    """
    if not text.strip():
        raise ValueError('Nonempty text required')
    return {'matches': store.find(work_id, text), 'sources': store.sources(work_id)}


@mcp.tool()
def upload_source(work_id: str, name: str, content_base64: str, expected_sha256: str) -> dict:
    """Attach immutable PDF or image bytes (max 32 MiB), verifying the SHA256 checksum."""
    return store.upload_source(work_id, name, content_base64, expected_sha256)


@mcp.tool()
def render_source_page(source_id: str, page: int, scale: float = 1.5) -> Image:
    """Return a PNG for visual reading. Physical page number starts at 1, not printed folio."""
    return Image(data=store.render(source_id, page, scale), format='png')


@mcp.tool()
def list_analyzers(language: str) -> dict:
    """Enumerate analyzer IDs and availability for explicit selection. No automatic substitution."""
    entries = available(language)
    return {'count': len(entries), 'availableCount': sum(a['available'] for a in entries), 'analyzers': entries}


@mcp.tool()
def analyze_sentence(language: str, analyzer: str, text: str, refresh: bool = False) -> dict:
    """Analyze ONE sentence, retaining all alternative analyses. Returns an attested runId.
    Successes are cached; transport/format errors are never treated as unknown words.
    """
    return store.run(language, analyzer, text, refresh)


@mcp.tool()
def compare_hypotheses(language: str, analyzer: str, sentences: list[str]) -> dict:
    """Analyze up to 8 alternative transcriptions, one sentence per request.
    Coverage is evidence, NOT proof of spelling: inspect original page before saving.
    """
    if not 1 <= len(sentences) <= 8:
        raise ValueError('Supply 1..8 hypotheses')
    return {'hypotheses': [store.run(language, analyzer, text) for text in sentences]}


@mcp.tool()
def append_sentences(work_id: str, documents: list[dict], run_ids: list[str]) -> dict:
    """Append analyzed sentences in order, atomically. Each document contains text, tokens,
    trailing, sourceRefs [{sourceId,page}]. Tokens use Unicode code-point offsets.
    Copy tokens from analyze_sentence; pageIndex/role/footnoteId may carry reader layout.
    """
    return {'sentenceIds': store.append(work_id, documents, run_ids)}


@mcp.tool()
def prepare_sentence_revision(sentence_id: str, run_id: str) -> dict:
    """Prepare (NOT save) a correction from an analyzer run. Retain sourceRefs, matched
    token layout and unchanged dictionary translations. Review inferred layout warnings.
    """
    return store.prepare(sentence_id, run_id)


@mcp.tool()
def get_sentence_revision(sentence_id: str, revision: int) -> dict:
    """Retrieve a historical document for comparison; never silently overwrite current data."""
    return store.revision(sentence_id, revision)


@mcp.tool()
def validate_sentence(sentence_id: str, expected_revision: int, document: dict,
                      reason: str, run_id: str | None = None) -> dict:
    """Dry-run exactly the save validator: text reconstruction, offsets, source ownership,
    morphology attestation and revision conflict. Cannot prove visual transcription truth.
    """
    return store.revise(sentence_id, expected_revision, document, reason, run_id, True)


@mcp.tool()
def save_sentence(sentence_id: str, expected_revision: int, document: dict,
                  reason: str, run_id: str | None = None) -> dict:
    """Validate and save atomically with history. Changed text/analyses require a runId.
    Preserve sourceRefs, layout and unchanged annotations. Never edits a published edition.
    """
    return store.revise(sentence_id, expected_revision, document, reason, run_id)


@mcp.tool()
def sentence_history(sentence_id: str) -> dict:
    """Read revision history and reasons. Old documents remain stored for audit."""
    return {'revisions': store.history(sentence_id)}


@mcp.tool()
def split_sentence(sentence_id: str, expected_revision: int, after_token: int, reason: str) -> dict:
    """Split a mistakenly joined sentence/heading after N tokens, losslessly. Preserve
    all analyses and source references, shift later ordinals, retain revision history.
    """
    return store.split(sentence_id, expected_revision, after_token, reason)


@mcp.tool()
def publish_work(work_id: str, expected_revision: int) -> dict:
    """Publish an immutable NEW reader edition. Existing book IDs/statistics remain untouched."""
    return store.publish(work_id, expected_revision)


if __name__ == '__main__':
    store.migrate()
    mcp.run(transport='stdio')
