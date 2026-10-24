"""Sentence analyzers. Keep transport failures distinct from unknown words."""
import os
import shutil
import subprocess
import threading
import time
from pathlib import Path

import httpx
import regex
from bs4 import BeautifulSoup

TOKEN = regex.compile(r"\p{L}[\p{L}\p{M}]*(?:[-'][\p{L}\p{M}]+)*|\p{N}+|[^\s]")
VIENNA_LOCK = threading.Lock()


def tokenize(text):
    result, cursor = [], 0
    for match in TOKEN.finditer(text):
        result.append(dict(prefix=text[cursor:match.start()], surface=match.group(),
                           start=match.start(), end=match.end(), analyses=[], analysis='Unknown'))
        cursor = match.end()
    return result, text[cursor:]


def available(language):
    result = []
    if language == 'mhr':
        result.append(dict(id='vienna', language=language, remote=True, available=True,
                           input='sentence', endpoint='https://mari-language.univie.ac.at/analyzer.php?int=0'))
        model = os.environ.get('MHR_HFST_MODEL', '/opt/uqureader/models/mhr-analyser-gt-desc.hfstol')
        result.append(dict(id='giella-hfst', language=language, remote=False,
                           available=bool(shutil.which('hfst-lookup') and Path(model).is_file()),
                           model=model, input='sentence'))
    if language == 'tt':
        result.append(dict(id='uqureader-tatar', language=language, remote=False, available=True,
                           input='sentence', endpoint=os.environ.get('READER_URL', 'http://127.0.0.1:8091')))
    return result


def vienna(text):
    # One request per sentence, serialized across workers of this server process.
    with VIENNA_LOCK:
        response = httpx.post('https://mari-language.univie.ac.at/analyzer.php?int=0',
                              data={'inField': text, 'dnt': 'dnt'}, timeout=90)
        response.raise_for_status()
        time.sleep(.35)
    sequence = []
    for cell in response.text.split('<!--NEXTWORD-->'):
        soup = BeautifulSoup(cell, 'html.parser')
        variants, word = [], ''
        for table in soup.find_all('table'):
            head = table.select_one('tr.headword')
            if head is None or head.find_parent('table') is not table:
                continue
            match = regex.search(r'\p{Script=Cyrillic}+(?:[-\x27]\p{Script=Cyrillic}+)*', head.get_text(' ', strip=True))
            if not match:
                continue
            word = match.group().casefold()
            def cells(cls):
                row = table.select_one('tr.' + cls)
                return [td.get_text(' ', strip=True) for td in row.find_all('td')] if row else []
            segments, gloss, pos = cells('div'), cells('gloss'), cells('pos')
            if not segments or not any(p and p != 'na' for p in pos):
                continue
            lemma = segments[0]
            entry = dict(lemma=lemma, segments=segments, gloss=gloss, pos=pos,
                         analysis=lemma + '+' + '+'.join(pos) + '|' + '+'.join(gloss)
                         + ' (' + '-'.join(segments) + ')')
            if entry not in variants:
                variants.append(entry)
        if word:
            sequence.append((word, variants))
    if not sequence:
        raise ValueError('Vienna returned no word tables; response format or service failure')
    return sequence


def analyze(language, analyzer, text):
    entry = next((a for a in available(language) if a['id'] == analyzer and a['available']), None)
    if entry is None:
        raise ValueError('Analyzer not available for this language')
    if not text.strip() or len(text) > 12000:
        raise ValueError('Expected one nonempty sentence, at most 12000 characters')
    tokens, trailing = tokenize(text)
    words = [t for t in tokens if regex.search(r'\p{L}', t['surface'])]
    if analyzer == 'vienna':
        sequence = vienna(text)
        cursor = 0
        for token in words:
            if cursor < len(sequence) and sequence[cursor][0] == token['surface'].casefold():
                token['analyses'] = sequence[cursor][1]
                cursor += 1
            elif any(w == token['surface'].casefold() for w, _ in sequence[cursor:]):
                raise ValueError('Vienna token alignment mismatch; refusing shifted analyses')
        if cursor != len(sequence):
            raise ValueError('Unconsumed Vienna words; refusing partial alignment')
    elif analyzer == 'giella-hfst':
        response = subprocess.run(['hfst-lookup', '-q', entry['model']],
                                  input='\n'.join(t['surface'].casefold() for t in words) + '\n',
                                  capture_output=True, text=True, encoding='utf-8', timeout=90, check=True)
        lookup = {}
        for line in response.stdout.splitlines():
            fields = line.split('\t')
            if len(fields) >= 2 and '+?' not in fields[1] and fields[-1] != 'inf':
                analysis = fields[1]
                lookup.setdefault(fields[0], []).append(dict(lemma=analysis.split('+')[0], analysis=analysis))
        for token in words:
            token['analyses'] = lookup.get(token['surface'].casefold(), [])
    else:
        response = httpx.post(entry['endpoint'] + '/api/text', json={'text': text}, timeout=90)
        response.raise_for_status()
        pairs = [pair for sentence in response.json().get('sentences', []) for pair in sentence]
        lookup = {pair[0]: pair[1] for pair in pairs}
        for token in words:
            tag = lookup.get(token['surface'], 'Unknown')
            if tag not in ('Unknown', 'Error', ''):
                token['analyses'] = [dict(lemma=tag.split('+')[0], analysis=tag)]
    for token in tokens:
        token['analysis'] = token['analyses'][0]['analysis'] if token['analyses'] else (
            'Unknown' if regex.search(r'\p{L}', token['surface']) else 'Sign')
        token['analyzer'] = analyzer
    unknown = [i for i, t in enumerate(tokens) if regex.search(r'\p{L}', t['surface']) and not t['analyses']]
    return dict(text=text, tokens=tokens, trailing=trailing, unknownIndexes=unknown,
                wordCount=len(words), recognizedCount=len(words) - len(unknown))
