import json
from pathlib import Path
from store import Store, validate_document

store = Store()
for work in store.works():
    asset = work['metadata'].get('readerAsset')
    if not asset:
        continue
    with store.connect() as db:
        rows = db.execute('SELECT * FROM editorial.sentences WHERE work_id=%s ORDER BY ordinal', (work['id'],)).fetchall()
        imported = db.execute("SELECT r.document FROM editorial.sentences s JOIN editorial.revisions r ON r.sentence_id=s.id AND r.revision=1 WHERE s.work_id=%s AND r.reason LIKE 'Lossless import%%' ORDER BY s.ordinal", (work['id'],)).fetchall()
    originals = [json.loads(l) for l in (Path('/opt/uqureader/assets') / asset).read_text('utf-8').splitlines() if l.strip()]
    expected = ''.join(t.get('prefix', '') + t['surface'] for t in originals)
    baseline = ''.join(row['document']['text'] for row in imported)
    actual = ''.join(row['document']['text'] for row in rows)
    assert baseline == expected, 'Historical import differs from original asset: ' + asset
    sources = {s['id']: s['page_count'] for s in store.sources(work['id'])}
    for row in rows:
        assert not validate_document(row['document'], sources), row['id']
        if sources:
            assert row['document']['sourceRefs'], row['id']
    print(json.dumps({'asset': asset, 'sentences': len(rows), 'chars': len(actual),
                      'originalImportPreserved': True, 'currentTextUnchanged': actual == expected,
                      'revisedSentences': sum(r['revision'] > 1 for r in rows),
                      'mappedSentences': sum(bool(r['document']['sourceRefs']) for r in rows)}), flush=True)
