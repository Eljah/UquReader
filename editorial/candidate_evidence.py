"""Locate candidate source lines and render unmodified PDF excerpts for review."""
import difflib
import json
import re
from pathlib import Path

import pymupdf

from store import Store

store = Store()
work_id = '43be7044888e4e938575110b818ca1d2'
scan = json.loads((store.root / ('scan-' + work_id + '-r64.json')).read_text())
with store.connect() as db:
    rows = {r['id']: r for r in db.execute('SELECT * FROM editorial.sentences WHERE work_id=%s', (work_id,)).fetchall()}
source_id = next(iter(rows.values()))['document']['sourceRefs'][0]['sourceId']
pdf = pymupdf.open(store.root / source_id)
out = store.root / 'candidate-review-r64'
out.mkdir(exist_ok=True)
norm = lambda text: re.sub(r'[^\wӧӱҥ]', '', text.casefold())
pages = {}
for p in range(len(pdf)):
    lines = []
    for block in pdf[p].get_text('dict')['blocks']:
        for line in block.get('lines', []):
            text = ''.join(s['text'] for s in line['spans'])
            if text.strip():
                lines.append({'text': text, 'bbox': line['bbox']})
    pages[p+1] = lines
items = []
for s in scan['sentences']:
    row = rows[s['sentenceId']]
    assert row['revision'] == s['revision'], 'Snapshot revision changed'
    for c in s['joinCandidates']:
        tokens = row['document']['tokens']
        left, right = tokens[c['fromToken']], tokens[c['toToken']]
        a, b = c['fromToken'], c['toToken']
        context = ''.join(t.get('prefix', '') + t['surface'] for t in tokens[max(0,a-3):b+4])
        target = norm(context)
        best = None
        for ref in row['document']['sourceRefs']:
            lines = pages[ref['page']]
            for i in range(len(lines)):
                for length in (1,2,3):
                    selected = lines[i:i+length]
                    text = ' '.join(l['text'] for l in selected)
                    value = norm(text)
                    ratio = difflib.SequenceMatcher(None, target, value, autojunk=False).ratio()
                    if norm(left['surface']) in value and norm(right['surface']) in value:
                        ratio += .5
                    if target in value:
                        ratio += 1
                    if best is None or ratio > best['score']:
                        best = {'score': ratio, 'page': ref['page'], 'line': i, 'length': length,
                                'text': text, 'boxes': [l['bbox'] for l in selected]}
        items.append({'id': len(items)+1, 'sentenceId': row['id'], 'revision': row['revision'],
                      'ordinal': row['ordinal'], 'fromToken': a, 'toToken': b,
                      'left': left['surface'], 'right': right['surface'], 'joined': c['joined'],
                      'context': context, 'sourceRefs': row['document']['sourceRefs'], 'location': best})
(out / 'candidates.json').write_text(json.dumps(items, ensure_ascii=False, indent=2), 'utf-8')
print(json.dumps({'count':len(items), 'output':str(out), 'sampleLines':pages[11][:5]}, ensure_ascii=False), flush=True)
