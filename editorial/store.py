"""Transactional editorial storage, independent of reader event ordinals."""
import base64
import copy
import difflib
import hashlib
import json
import os
import uuid
from pathlib import Path

import pymupdf as fitz
import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from analyzers import analyze


def identity():
    return uuid.uuid4().hex


def validate_document(doc, source_pages, trusted=None):
    errors = []
    if not isinstance(doc, dict):
        return ['Document must be an object']
    text, tokens = doc.get('text'), doc.get('tokens')
    if not isinstance(text, str) or not text.strip() or len(text) > 12000:
        errors.append('Text must contain 1..12000 characters')
    if not isinstance(tokens, list) or not tokens:
        return errors + ['Nonempty tokens required']
    cursor, pieces = 0, []
    for i, token in enumerate(tokens):
        if not isinstance(token, dict):
            errors.append(f'Token {i}: expected object')
            continue
        prefix, surface = token.get('prefix', ''), token.get('surface')
        if not isinstance(prefix, str) or not isinstance(surface, str) or not surface:
            errors.append(f'Token {i}: invalid prefix/surface')
            continue
        if prefix.strip():
            errors.append(f'Token {i}: prefix must contain whitespace only')
        cursor += len(prefix)
        if token.get('start') != cursor or token.get('end') != cursor + len(surface):
            errors.append(f'Token {i}: invalid Unicode character offsets')
        cursor += len(surface)
        pieces.extend((prefix, surface))
        if not isinstance(token.get('analysis'), str) or not isinstance(token.get('analyses', []), list):
            errors.append(f'Token {i}: malformed analyses')
        for variant in token.get('analyses', []) if isinstance(token.get('analyses', []), list) else []:
            if not isinstance(variant, dict) or not isinstance(variant.get('analysis'), str) or not isinstance(variant.get('lemma'), str):
                errors.append(f'Token {i}: malformed analysis variant')
        if 'pageIndex' in token and (type(token['pageIndex']) is not int or not 0 <= token['pageIndex'] <= 10000):
            errors.append(f'Token {i}: invalid reader pageIndex')
        if token.get('role', 'body') not in ('body', 'footnote', 'heading', ''):
            errors.append(f'Token {i}: invalid role')
        if not isinstance(token.get('footnoteId', ''), str):
            errors.append(f'Token {i}: invalid footnoteId')
        if not isinstance(token.get('translations', []), list) or any(not isinstance(s, str) for s in token.get('translations', [])):
            errors.append(f'Token {i}: invalid translations')
    trailing = doc.get('trailing', '')
    if not isinstance(trailing, str) or trailing.strip():
        errors.append('Trailing must contain whitespace only')
    elif ''.join(pieces) + trailing != text:
        errors.append('Tokens do not reconstruct text exactly')
    refs = doc.get('sourceRefs', [])
    if not isinstance(refs, list):
        errors.append('sourceRefs must be an array')
        refs = []
    for ref in refs:
        if not isinstance(ref, dict) or not isinstance(ref.get('sourceId'), str) or type(ref.get('page')) is not int or not 1 <= ref['page'] <= source_pages.get(ref['sourceId'], 0):
            errors.append('Source page missing, outside PDF, or belongs to another work')
    if trusted is not None and not errors:
        # Only the backend can attest morphology; agent-provided tags are not evidence.
        def signatures(value):
            return [(t.get('surface'), t.get('analysis'),
                     [{k: a.get(k) for k in ('lemma', 'analysis', 'segments', 'gloss', 'pos')}
                      for a in t.get('analyses', [])]) for t in value['tokens']]
        if text != trusted.get('text') or signatures(doc) != signatures(trusted):
            errors.append('Text/analyses differ from the attested analyzer run or previous revision')
    return errors


class Store:
    def __init__(self):
        self.root = Path(os.environ.get('EDITOR_DATA_DIR', '/opt/uqureader/editor-data'))
        self.root.mkdir(parents=True, exist_ok=True)
        self.dsn = os.environ.get('EDITOR_DATABASE_URL') or os.environ['DATABASE_URL']
        self.dsn = self.dsn.removeprefix('jdbc:')

    def connect(self):
        return psycopg.connect(self.dsn, row_factory=dict_row)

    def migrate(self):
        with self.connect() as db:
            db.execute("SELECT pg_advisory_xact_lock(hashtext('uqureader.editorial.schema'))")
            db.execute(Path(__file__).with_name('schema.sql').read_text('utf-8'))

    def works(self):
        with self.connect() as db:
            return db.execute('SELECT * FROM editorial.works ORDER BY title').fetchall()

    def create(self, title, language, metadata=None):
        if not title.strip() or language not in ('mhr', 'tt'):
            raise ValueError('Nonempty title and language mhr/tt required')
        work_id = identity()
        with self.connect() as db:
            db.execute('INSERT INTO editorial.works(id,title,language,metadata) VALUES (%s,%s,%s,%s)',
                       (work_id, title, language, Jsonb(metadata or {})))
        return {'workId': work_id, 'revision': 1}

    def sentences(self, work_id, offset=0, limit=25, unknown_only=False):
        if offset < 0 or not 1 <= limit <= 100:
            raise ValueError('offset >= 0; limit 1..100')
        condition = " AND EXISTS (SELECT 1 FROM jsonb_array_elements(document->'tokens') t WHERE t->>'analysis' IN ('Unknown','Error','') OR t->>'analysis' LIKE '%%+na|%%' OR t->>'analysis' LIKE '%%+?%%')" if unknown_only else ''
        with self.connect() as db:
            return db.execute('SELECT * FROM editorial.sentences WHERE work_id=%s' + condition +
                              ' ORDER BY ordinal LIMIT %s OFFSET %s', (work_id, limit, offset)).fetchall()

    def find(self, work_id, text):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM editorial.sentences WHERE work_id=%s AND document->>'text'=%s ORDER BY ordinal", (work_id, text)).fetchall()
            if not rows:
                rows = db.execute("SELECT * FROM editorial.sentences WHERE work_id=%s AND position(%s in document->>'text') > 0 ORDER BY ordinal LIMIT 50", (work_id, text)).fetchall()
            return rows

    def get(self, sentence_id):
        with self.connect() as db:
            row = db.execute('SELECT * FROM editorial.sentences WHERE id=%s', (sentence_id,)).fetchone()
            if not row:
                raise ValueError('Sentence not found')
            return row

    def sources(self, work_id):
        with self.connect() as db:
            return db.execute('SELECT * FROM editorial.sources WHERE work_id=%s ORDER BY name', (work_id,)).fetchall()

    def upload_source(self, work_id, name, content_base64, expected_sha256):
        if len(content_base64) > 45 * 1024 * 1024:
            raise ValueError('Encoded source exceeds 32 MiB binary limit')
        data = base64.b64decode(content_base64, validate=True)
        if not data or len(data) > 32 * 1024 * 1024:
            raise ValueError('Source size must be 1..32 MiB')
        digest = hashlib.sha256(data).hexdigest()
        if digest != expected_sha256:
            raise ValueError('SHA256 mismatch')
        with fitz.open(stream=data) as pdf:
            pages = len(pdf)
            mime = 'application/pdf' if pdf.is_pdf else 'image/' + pdf.metadata.get('format', 'unknown').lower()
            if pages < 1 or pdf.needs_pass:
                raise ValueError('Empty or encrypted source')
        source_id = identity()
        with self.connect() as db:
            db.execute('INSERT INTO editorial.sources(id,work_id,name,sha256,mime,page_count) VALUES (%s,%s,%s,%s,%s,%s)',
                       (source_id, work_id, Path(name).name, digest, mime, pages))
            (self.root / source_id).write_bytes(data)
        return {'sourceId': source_id, 'pageCount': pages, 'sha256': digest}

    def render(self, source_id, page, scale=1.5):
        if not 0.5 <= scale <= 2:
            raise ValueError('scale must be 0.5..2')
        with self.connect() as db:
            source = db.execute('SELECT * FROM editorial.sources WHERE id=%s', (source_id,)).fetchone()
        if not source or not 1 <= page <= source['page_count']:
            raise ValueError('Unknown source/page')
        with fitz.open(self.root / source_id) as pdf:
            p = pdf[page - 1]
            if p.rect.width * p.rect.height * scale * scale > 16000000:
                raise ValueError('Rendered page too large')
            return p.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False).tobytes('png')

    def run(self, language, analyzer, text, refresh=False):
        with self.connect() as db:
            cached = db.execute('SELECT id,result FROM editorial.analysis_runs WHERE language=%s AND analyzer=%s AND md5(input)=md5(%s) AND input=%s ORDER BY created_at DESC LIMIT 1',
                                (language, analyzer, text, text)).fetchone()
        if cached and not refresh:
            return {'runId': cached['id'], **cached['result'], 'cached': True}
        result = analyze(language, analyzer, text)
        run_id = identity()
        with self.connect() as db:
            db.execute('INSERT INTO editorial.analysis_runs(id,language,analyzer,input,result) VALUES (%s,%s,%s,%s,%s)',
                       (run_id, language, analyzer, text, Jsonb(result)))
        return {'runId': run_id, **result, 'cached': False}

    def check(self, db, work_id, document, run_id=None, old=None):
        sources = db.execute('SELECT id,page_count FROM editorial.sources WHERE work_id=%s', (work_id,)).fetchall()
        trusted = old
        if run_id:
            row = db.execute('SELECT r.result FROM editorial.analysis_runs r JOIN editorial.works w ON w.language=r.language WHERE r.id=%s AND w.id=%s', (run_id, work_id)).fetchone()
            if not row:
                return ['Analyzer run not found for this language']
            trusted = row['result']
        if trusted is None:
            return ['An attested analyzer run is required']
        return validate_document(document, {s['id']: s['page_count'] for s in sources}, trusted)

    def prepare(self, sentence_id, run_id):
        original = self.get(sentence_id)
        with self.connect() as db:
            run = db.execute('SELECT r.result FROM editorial.analysis_runs r JOIN editorial.works w ON r.language=w.language WHERE r.id=%s AND w.id=%s', (run_id, original['work_id'])).fetchone()
        if not run:
            raise ValueError('Analyzer run not found for this language')
        doc = copy.deepcopy(original['document'])
        old = doc['tokens']
        for key in ('text', 'tokens', 'trailing'):
            doc[key] = copy.deepcopy(run['result'][key])
        matching = difflib.SequenceMatcher(a=[t['surface'] for t in old], b=[t['surface'] for t in doc['tokens']], autojunk=False)
        matched = {}
        for a, b, size in matching.get_matching_blocks():
            for offset in range(size):
                matched[b + offset] = old[a + offset]
        warnings = []
        for index, token in enumerate(doc['tokens']):
            previous = matched.get(index)
            if previous is None:
                previous = matched[min(matched, key=lambda n: abs(n - index))] if matched else old[0]
                warnings.append(f'Token {index}: inferred layout, verify page/footnote placement')
            for key in ('pageIndex', 'sourcePage', 'role', 'footnoteId'):
                if key in previous:
                    token[key] = previous[key]
            if index in matched:
                same_lemmas = {v.get('lemma') for v in token.get('analyses', [])} == {v.get('lemma') for v in previous.get('analyses', [])}
                if same_lemmas and 'translations' in previous:
                    token['translations'] = previous['translations']
                for variant in token.get('analyses', []):
                    preserved = next((v for v in previous.get('analyses', []) if v.get('analysis') == variant.get('analysis')), None)
                    if preserved and 'translations' in preserved:
                        variant['translations'] = preserved['translations']
        return {'sentenceId': sentence_id, 'expectedRevision': original['revision'], 'runId': run_id,
                'document': doc, 'warnings': warnings, 'saved': False}

    def append(self, work_id, documents, run_ids):
        if not 1 <= len(documents) <= 100 or len(documents) != len(run_ids):
            raise ValueError('Supply 1..100 documents and corresponding run IDs')
        result = []
        with self.connect() as db:
            if not db.execute('SELECT id FROM editorial.works WHERE id=%s FOR UPDATE', (work_id,)).fetchone():
                raise ValueError('Work not found')
            ordinal = db.execute('SELECT coalesce(max(ordinal),-1)+1 AS n FROM editorial.sentences WHERE work_id=%s', (work_id,)).fetchone()['n']
            for doc, run_id in zip(documents, run_ids):
                errors = self.check(db, work_id, doc, run_id)
                if errors:
                    raise ValueError('; '.join(errors))
                sid = identity()
                db.execute('INSERT INTO editorial.sentences(id,work_id,ordinal,document) VALUES (%s,%s,%s,%s)', (sid, work_id, ordinal, Jsonb(doc)))
                db.execute('INSERT INTO editorial.revisions(sentence_id,revision,document,reason) VALUES (%s,1,%s,%s)', (sid, Jsonb(doc), 'Initial import'))
                result.append(sid)
                ordinal += 1
            db.execute('UPDATE editorial.works SET revision=revision+1 WHERE id=%s', (work_id,))
        return result

    def revise(self, sentence_id, expected_revision, document, reason, run_id=None, dry_run=False):
        if not reason.strip():
            raise ValueError('Reason and visual evidence description required')
        with self.connect() as db:
            original = db.execute('SELECT work_id FROM editorial.sentences WHERE id=%s', (sentence_id,)).fetchone()
            if not original:
                raise ValueError('Sentence not found')
            db.execute('SELECT id FROM editorial.works WHERE id=%s FOR UPDATE', (original['work_id'],))
            row = db.execute('SELECT * FROM editorial.sentences WHERE id=%s FOR UPDATE', (sentence_id,)).fetchone()
            if row['revision'] != expected_revision:
                raise ValueError('Revision conflict: reload sentence before editing')
            errors = self.check(db, row['work_id'], document, run_id, row['document'])
            if row['document'].get('sourceRefs') and not document.get('sourceRefs'):
                errors.append('Existing source references must not be removed')
            if dry_run or errors:
                return {'valid': not errors, 'errors': errors, 'saved': False}
            revision = expected_revision + 1
            db.execute('UPDATE editorial.sentences SET document=%s,revision=%s WHERE id=%s', (Jsonb(document), revision, sentence_id))
            db.execute('INSERT INTO editorial.revisions(sentence_id,revision,document,reason) VALUES (%s,%s,%s,%s)', (sentence_id, revision, Jsonb(document), reason + ('; run=' + run_id if run_id else '')))
            db.execute('UPDATE editorial.works SET revision=revision+1 WHERE id=%s', (row['work_id'],))
            return {'valid': True, 'saved': True, 'revision': revision}

    def history(self, sentence_id):
        with self.connect() as db:
            return db.execute('SELECT revision,reason,created_at FROM editorial.revisions WHERE sentence_id=%s ORDER BY revision DESC', (sentence_id,)).fetchall()

    def revision(self, sentence_id, revision):
        with self.connect() as db:
            row = db.execute('SELECT * FROM editorial.revisions WHERE sentence_id=%s AND revision=%s', (sentence_id, revision)).fetchone()
            if not row:
                raise ValueError('Revision not found')
            return row

    def split(self, sentence_id, expected_revision, after_token, reason):
        if not reason.strip():
            raise ValueError('Reason required')
        with self.connect() as db:
            location = db.execute('SELECT work_id FROM editorial.sentences WHERE id=%s', (sentence_id,)).fetchone()
            if not location:
                raise ValueError('Sentence not found')
            db.execute('SELECT id FROM editorial.works WHERE id=%s FOR UPDATE', (location['work_id'],))
            row = db.execute('SELECT * FROM editorial.sentences WHERE id=%s FOR UPDATE', (sentence_id,)).fetchone()
            if row['revision'] != expected_revision:
                raise ValueError('Revision conflict')
            old = row['document']
            if not 0 < after_token < len(old['tokens']):
                raise ValueError('Split must leave nonempty token lists on both sides')
            docs = []
            for tokens, trailing in ((old['tokens'][:after_token], ''), (old['tokens'][after_token:], old.get('trailing', ''))):
                doc = copy.deepcopy(old)
                doc['tokens'] = copy.deepcopy(tokens)
                doc['trailing'] = trailing
                cursor = 0
                for token in doc['tokens']:
                    cursor += len(token.get('prefix', ''))
                    token['start'] = cursor
                    cursor += len(token['surface'])
                    token['end'] = cursor
                doc['text'] = ''.join(t.get('prefix', '') + t['surface'] for t in tokens) + trailing
                errors = self.check(db, row['work_id'], doc, old=doc)
                if errors:
                    raise ValueError('; '.join(errors))
                docs.append(doc)
            assert docs[0]['text'] + docs[1]['text'] == old['text']
            # Temporary negative ordinals avoid immediate UNIQUE conflicts during shift.
            db.execute('UPDATE editorial.sentences SET ordinal=-ordinal-1 WHERE work_id=%s AND ordinal>%s', (row['work_id'], row['ordinal']))
            db.execute('UPDATE editorial.sentences SET ordinal=-ordinal WHERE work_id=%s AND ordinal<0', (row['work_id'],))
            new_id = identity()
            db.execute('UPDATE editorial.sentences SET document=%s,revision=revision+1 WHERE id=%s', (Jsonb(docs[0]), sentence_id))
            db.execute('INSERT INTO editorial.sentences(id,work_id,ordinal,document) VALUES (%s,%s,%s,%s)', (new_id, row['work_id'], row['ordinal'] + 1, Jsonb(docs[1])))
            for sid, rev, doc in ((sentence_id, expected_revision + 1, docs[0]), (new_id, 1, docs[1])):
                db.execute('INSERT INTO editorial.revisions(sentence_id,revision,document,reason) VALUES (%s,%s,%s,%s)', (sid, rev, Jsonb(doc), reason + '; split from=' + sentence_id))
            db.execute('UPDATE editorial.works SET revision=revision+1 WHERE id=%s', (row['work_id'],))
        return {'sentenceId': sentence_id, 'revision': expected_revision + 1, 'nextSentenceId': new_id}

    def publish(self, work_id, expected_revision):
        directory = Path(os.environ['EDITOR_PUBLISH_DIR'])
        directory.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            work = db.execute('SELECT * FROM editorial.works WHERE id=%s FOR UPDATE', (work_id,)).fetchone()
            if not work or work['revision'] != expected_revision:
                raise ValueError('Work revision conflict')
            rows = db.execute('SELECT * FROM editorial.sentences WHERE work_id=%s ORDER BY ordinal', (work_id,)).fetchall()
            if not rows:
                raise ValueError('Cannot publish an empty work')
            edition = 'edition_' + work_id + '_r' + str(expected_revision)
            lines = []
            pending_whitespace = ''
            for row in rows:
                doc = row['document']
                errors = self.check(db, work_id, doc, old=doc)
                if errors:
                    raise ValueError('; '.join(errors))
                for index, token in enumerate(doc['tokens']):
                    record = {**token, 'sentenceId': row['id'], 'sentenceOrdinal': row['ordinal'],
                              'sourceRefs': doc.get('sourceRefs', [])}
                    if index == 0 and pending_whitespace:
                        record['prefix'] = pending_whitespace + record.get('prefix', '')
                    lines.append(json.dumps(record, ensure_ascii=False))
                pending_whitespace = doc.get('trailing', '')
            if pending_whitespace:
                last = rows[-1]
                token = last['document']['tokens'][-1]
                lines.append(json.dumps({'prefix': '', 'surface': pending_whitespace, 'analysis': 'Sign',
                    'sentenceId': last['id'], 'sentenceOrdinal': last['ordinal'],
                    'pageIndex': token.get('pageIndex', -1), 'role': token.get('role', 'body'),
                    'sourceRefs': last['document'].get('sourceRefs', [])}, ensure_ascii=False))
            content = ('\n'.join(lines) + '\n').encode('utf-8')
            digest = hashlib.sha256(content).hexdigest()
            target = directory / (edition + '.ttmorph.jsonl')
            if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                raise ValueError('Published editions are immutable')
            temp = directory / (edition + '.tmp')
            temp.write_bytes(content)
            temp.replace(target)
            manifest = {'id': edition, 'title': work['title'] + ' [r' + str(expected_revision) + ']',
                        'language': work['language'], 'workId': work_id, 'revision': expected_revision, 'sha256': digest}
            temp.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
            temp.replace(directory / (edition + '.metadata.json'))
            db.execute('INSERT INTO editorial.publications(id,work_id,revision,sha256) VALUES (%s,%s,%s,%s) ON CONFLICT DO NOTHING', (edition, work_id, expected_revision, digest))
        return manifest
