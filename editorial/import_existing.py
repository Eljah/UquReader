"""Admin-only lossless import. Uses existing annotations, never re-OCRs the book."""
import argparse
import base64
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

from psycopg.types.json import Jsonb

from store import Store, identity, validate_document
from analyzers import tokenize


def import_book(store, path, title, language, pdf=None, extracted_text=None):
    records = [json.loads(line) for line in Path(path).read_text('utf-8').splitlines() if line.strip()]
    original_text = ''.join(r.get('prefix', '') + r['surface'] for r in records)
    expanded = []
    empty_records = 0
    for record in records:
        if record.get('surface') == '' and record.get('prefix', '') == '':
            empty_records += 1
            continue
        if record.get('prefix', '').strip():
            extra, trailing = tokenize(record['prefix'])
            for token in extra:
                token['analysis'] = 'Unknown' if any(c.isalpha() for c in token['surface']) else 'Sign'
                for key in ('pageIndex', 'sourcePage', 'role', 'footnoteId'):
                    if key in record:
                        token[key] = record[key]
            expanded.extend(extra)
            record['prefix'] = trailing
        expanded.append(record)
    records = expanded
    assert ''.join(r.get('prefix', '') + r['surface'] for r in records) == original_text
    digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    work_id = None
    for work in store.works():
        if work['metadata'].get('importSha256') == digest:
            if store.sentences(work['id'], limit=1):
                return {'workId': work['id'], 'alreadyImported': True}
            work_id = work['id']
    if work_id is None:
        work_id = store.create(title, language, {'importSha256': digest, 'readerAsset': Path(path).name,
                                               'sourceStatus': 'mapping-pending' if pdf else 'missing-pdf',
                                               'emptyLegacyRecords': empty_records})['workId']
    mapping, source_id = {}, None
    if pdf:
        data = Path(pdf).read_bytes()
        existing = next((s for s in store.sources(work_id) if s['sha256'] == hashlib.sha256(data).hexdigest()), None)
        source = {'sourceId': existing['id'], 'pageCount': existing['page_count']} if existing else store.upload_source(work_id, Path(pdf).name, base64.b64encode(data).decode(), hashlib.sha256(data).hexdigest())
        source_id = source['sourceId']
        # Use the SAME verified embedded-text extraction used by the importer.
        # Match content, never confuse printed folios with physical PDF pages.
        pages = Path(extracted_text).read_text('utf-8').split('\f')
        if len(pages) not in (source['pageCount'], source['pageCount'] + 1):
            raise ValueError('Extracted-text/PDF page count mismatch')
        words = lambda text: Counter(re.findall(r'[^\W\d_]+', text.casefold()))
        page_words = [words(p) for p in pages[:source['pageCount']]]
        previous = -1
        for reader_page in sorted({r['pageIndex'] for r in records}):
            sample = words(' '.join(r['surface'] for r in records if r['pageIndex'] == reader_page))
            scores = [(sum((sample & pw).values()) / max(1, sum(sample.values())), i)
                      for i, pw in enumerate(page_words) if i > previous]
            score, physical = max(scores, key=lambda item: (item[0], -item[1]))
            if score < .65:
                raise ValueError(f'Uncertain page mapping {reader_page}: score {score}; manual review required')
            mapping[reader_page] = {'sourceId': source_id, 'page': physical + 1}
            previous = physical
    groups, current = [], []
    for record in records:
        # Footnotes remain independent units, never spliced into body sentences.
        if current and (record.get('role', 'body') != current[-1].get('role', 'body')
                        or record.get('footnoteId', '') != current[-1].get('footnoteId', '')
                        or (record.get('pageIndex') != current[-1].get('pageIndex')
                            and all(len(t['surface']) == 1 for t in current))
                        or ('\n\n' in record.get('prefix', '') and current[-1]['surface'] not in (',', ':', ';'))):
            groups.append(current)
            current = []
        current.append(record.copy())
        if record['surface'] in ('.', '!', '?', '\u2026'):
            groups.append(current)
            current = []
    if current:
        groups.append(current)
    with store.connect() as db:
        for ordinal, tokens in enumerate(groups):
            cursor, pieces, refs = 0, [], []
            for token in tokens:
                prefix = token.get('prefix', '')
                cursor += len(prefix)
                token['start'] = cursor
                cursor += len(token['surface'])
                token['end'] = cursor
                pieces.extend((prefix, token['surface']))
                ref = mapping.get(token.get('pageIndex'))
                if ref and ref not in refs:
                    refs.append(ref)
            doc = {'text': ''.join(pieces), 'tokens': tokens, 'trailing': '', 'sourceRefs': refs,
                   'kind': tokens[0].get('role', 'body')}
            errors = validate_document(doc, {source_id: source['pageCount']} if source_id else {})
            if errors:
                raise ValueError(f'Import unit {ordinal}: {errors}')
            sid = identity()
            db.execute('INSERT INTO editorial.sentences(id,work_id,ordinal,document) VALUES (%s,%s,%s,%s)', (sid, work_id, ordinal, Jsonb(doc)))
            db.execute('INSERT INTO editorial.revisions(sentence_id,revision,document,reason) VALUES (%s,1,%s,%s)', (sid, Jsonb(doc), 'Lossless import of existing verified annotation; sha256=' + digest))
        if source_id:
            db.execute("UPDATE editorial.works SET metadata=metadata || %s WHERE id=%s", (Jsonb({'sourceStatus': 'mapped', 'mappingMethod': 'embedded-text-content-alignment', 'physicalPageMap': mapping}), work_id))
    return {'workId': work_id, 'sentences': len(groups), 'tokens': len(records), 'mappedPages': len(mapping)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('jsonl')
    parser.add_argument('title')
    parser.add_argument('language')
    parser.add_argument('--pdf')
    parser.add_argument('--extracted-text')
    args = parser.parse_args()
    store = Store()
    store.migrate()
    print(json.dumps(import_book(store, args.jsonl, args.title, args.language, args.pdf, args.extracted_text), ensure_ascii=False))
