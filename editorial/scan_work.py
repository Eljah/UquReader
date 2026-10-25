"""Read-only whole-work HFST scan. Candidates require visual review, never autosave."""
import collections
import json
import subprocess
import sys
from pathlib import Path

import regex

from analyzers import available
from store import Store


def word(token):
    return bool(regex.fullmatch(r'\p{L}[\p{L}\p{M}-]*', token['surface']))


def unknown(token):
    analysis = token.get('analysis', '')
    return analysis in ('Unknown', 'Error', '') or '+***|' in analysis or '+?' in analysis


def scan(store, work_id):
    with store.connect() as db:
        work = db.execute('SELECT * FROM editorial.works WHERE id=%s', (work_id,)).fetchone()
        rows = db.execute('SELECT * FROM editorial.sentences WHERE work_id=%s AND merged_into IS NULL ORDER BY ordinal', (work_id,)).fetchall()
    analyzer = next(a for a in available(work['language']) if a['id'] == 'giella-hfst' and a['available'])
    forms, suggestions = set(), {}
    for row in rows:
        tokens = row['document']['tokens']
        forms.update(t['surface'].casefold() for t in tokens if word(t))
        pairs = []
        for i in range(1, len(tokens)):
            left, right = tokens[i-1], tokens[i]
            if word(left) and word(right) and (unknown(left) or unknown(right)):
                joined = left['surface'].rstrip('-') + right['surface']
                forms.add(joined.casefold())
                pairs.append((i-1, i, joined))
        suggestions[row['id']] = pairs
    # HFST is context-free: one process for all forms is equivalent to per-sentence
    # lookup, without reloading the 41 MiB transducer thousands of times.
    process = subprocess.run(['hfst-lookup', '-q', analyzer['model']], input='\n'.join(sorted(forms)) + '\n',
                             text=True, encoding='utf-8', capture_output=True, timeout=600, check=True)
    lookup = collections.defaultdict(list)
    for line in process.stdout.splitlines():
        fields = line.split('\t')
        if len(fields) >= 2 and '+?' not in fields[1] and fields[-1] != 'inf':
            if fields[1] not in lookup[fields[0]]:
                lookup[fields[0]].append(fields[1])
    results = []
    for row in rows:
        tokens = row['document']['tokens']
        missing = [i for i, t in enumerate(tokens) if word(t) and not lookup[t['surface'].casefold()]]
        fallback = [i for i, t in enumerate(tokens) if word(t) and unknown(t) and lookup[t['surface'].casefold()]]
        candidates = [{'fromToken': start, 'toToken': end, 'joined': joined, 'analyses': lookup[joined.casefold()]}
                      for start, end, joined in suggestions[row['id']] if lookup[joined.casefold()]]
        results.append({'sentenceId': row['id'], 'ordinal': row['ordinal'], 'revision': row['revision'],
                        'hfstUnknownIndexes': missing, 'viennaUnknownHfstKnownIndexes': fallback,
                        'joinCandidates': candidates, 'sourceRefs': row['document']['sourceRefs'],
                        'visualReviewed': False, 'saved': False})
    summary = {'workId': work_id, 'workRevision': work['revision'], 'analyzer': 'giella-hfst',
               'unitsScanned': len(rows), 'formsQueriedIncludingCandidates': len(forms),
               'hfstRecognizedForms': len([a for a in lookup.values() if a]),
               'viennaUnknownHfstKnownTokens': sum(len(r['viennaUnknownHfstKnownIndexes']) for r in results),
               'joinCandidates': sum(len(r['joinCandidates']) for r in results),
               'automaticChanges': 0, 'visualReviewComplete': False}
    target = store.root / ('scan-' + work_id + '-r' + str(work['revision']) + '.json')
    target.write_text(json.dumps({'summary': summary, 'sentences': results}, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({'report': str(target), **summary}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    scan(Store(), sys.argv[1])
