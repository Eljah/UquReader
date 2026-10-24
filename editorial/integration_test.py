"""Run against deployment DB as service user. Deletes only its own generated fixture."""
import base64
import copy
import hashlib
import json
import os
import tempfile
from pathlib import Path

import pymupdf

from store import Store

store = Store()
store.migrate()
work_id = store.create('EDITORIAL INTEGRATION FIXTURE', 'mhr')['workId']
source_id = None
image_id = None
try:
    analysis = store.run('mhr', 'vienna', '\u0428\u043e\u0448\u043e.')
    pdf = pymupdf.open()
    pdf.new_page().insert_text((50, 50), 'Source fixture')
    payload = pdf.tobytes()
    source = store.upload_source(work_id, 'fixture.pdf', base64.b64encode(payload).decode(), hashlib.sha256(payload).hexdigest())
    source_id = source['sourceId']
    png = store.render(source_id, 1)
    assert png.startswith(b'\x89PNG')
    image_id = store.upload_source(work_id, 'fixture.png', base64.b64encode(png).decode(), hashlib.sha256(png).hexdigest())['sourceId']
    assert store.render(image_id, 1).startswith(b'\x89PNG')
    doc = {k: analysis[k] for k in ('text', 'tokens', 'trailing')}
    doc['sourceRefs'] = [{'sourceId': source_id, 'page': 1}]
    sid = store.append(work_id, [doc], [analysis['runId']])[0]
    assert len(store.find(work_id, doc['text'])) == 1
    assert store.revise(sid, 1, doc, 'Same text, reviewed source', dry_run=True)['valid']
    bad = copy.deepcopy(doc)
    bad['tokens'][0]['analysis'] = 'invented'
    assert not store.revise(sid, 1, bad, 'Bad annotation', analysis['runId'])['valid']
    bad = copy.deepcopy(doc)
    bad['sourceRefs'][0]['page'] = 2
    assert not store.revise(sid, 1, bad, 'Bad page')['valid']
    assert store.revise(sid, 1, doc, 'Reviewed source')['saved']
    try:
        store.revise(sid, 1, doc, 'Stale writer')
        raise AssertionError('Stale write accepted')
    except ValueError as error:
        assert 'conflict' in str(error)
    assert len(store.history(sid)) == 2
    assert store.prepare(sid, analysis['runId'])['document']['sourceRefs'] == doc['sourceRefs']
    assert store.revision(sid, 1)['document'] == doc
    revision = next(w['revision'] for w in store.works() if w['id'] == work_id)
    with tempfile.TemporaryDirectory() as temp:
        os.environ['EDITOR_PUBLISH_DIR'] = temp
        publication = store.publish(work_id, revision)
        asset = Path(temp) / (publication['id'] + '.ttmorph.jsonl')
        rows = [json.loads(line) for line in asset.read_text().splitlines()]
        assert ''.join(r.get('prefix', '') + r['surface'] for r in rows) == doc['text']
        assert all(r['sentenceId'] == sid and r['sourceRefs'] == doc['sourceRefs'] for r in rows)
        assert store.publish(work_id, revision) == publication
    split = store.split(sid, 2, 1, 'Integration test: lossless token boundary split')
    assert store.get(sid)['document']['text'] + store.get(split['nextSentenceId'])['document']['text'] == doc['text']
    print('PASS: PostgreSQL, source upload/render, append, attestation, source bounds, stale write, history, immutable publication')
finally:
    with store.connect() as db:
        db.execute('DELETE FROM editorial.publications WHERE work_id=%s', (work_id,))
        db.execute('DELETE FROM editorial.revisions WHERE sentence_id IN (SELECT id FROM editorial.sentences WHERE work_id=%s)', (work_id,))
        db.execute('DELETE FROM editorial.sentences WHERE work_id=%s', (work_id,))
        db.execute('DELETE FROM editorial.sources WHERE work_id=%s', (work_id,))
        db.execute('DELETE FROM editorial.works WHERE id=%s', (work_id,))
    if source_id:
        (store.root / source_id).unlink(missing_ok=True)
    if image_id:
        (store.root / image_id).unlink(missing_ok=True)
