"""Read-only HFST hypotheses across provisional sentence boundaries."""
import collections
import json
import subprocess
import sys

from analyzers import available
from scan_work import word, unknown
from store import Store


def scan(store, work_id):
    with store.connect() as db:
        work = db.execute('SELECT * FROM editorial.works WHERE id=%s', (work_id,)).fetchone()
        rows = db.execute('SELECT * FROM editorial.sentences WHERE work_id=%s AND merged_into IS NULL ORDER BY ordinal', (work_id,)).fetchall()
    model = next(a['model'] for a in available(work['language']) if a['id'] == 'giella-hfst' and a['available'])
    forms, hypotheses = set(), []
    previous = None
    for index, row in enumerate(rows):
        words = [t for t in row['document']['tokens'] if word(t)]
        if not words:
            continue
        if previous is not None:
            start, left = previous
            right = words[0]
            joined = left['surface'].rstrip('-') + right['surface']
            forms.update((left['surface'].casefold(), right['surface'].casefold(), joined.casefold()))
            hypotheses.append({'sentenceIds': [r['id'] for r in rows[start:index+1]],
                               'ordinals': [r['ordinal'] for r in rows[start:index+1]],
                               'left': left['surface'], 'right': right['surface'], 'joined': joined,
                               'unknownEdge': unknown(left) or unknown(right),
                               'sourceRefs': [r['document'].get('sourceRefs', []) for r in rows[start:index+1]],
                               'leftContext': rows[start]['document']['text'], 'rightContext': row['document']['text'],
                               'visualReviewed': False, 'saved': False})
        previous = (index, words[-1])
    process = subprocess.run(['hfst-lookup', '-q', model], input='\n'.join(sorted(forms))+'\n',
                             text=True, encoding='utf-8', capture_output=True, timeout=600, check=True)
    lookup = collections.defaultdict(list)
    for line in process.stdout.splitlines():
        fields = line.split('\t')
        if len(fields) >= 2 and '+?' not in fields[1] and fields[-1] != 'inf':
            if fields[1] not in lookup[fields[0]]:
                lookup[fields[0]].append(fields[1])
    candidates = []
    for h in hypotheses:
        if lookup[h['joined'].casefold()]:
            candidates.append({**h, 'analyses': lookup[h['joined'].casefold()],
                               'leftAnalyses': lookup[h['left'].casefold()],
                               'rightAnalyses': lookup[h['right'].casefold()]})
    report = {'summary': {'workId': work_id, 'revision': work['revision'], 'records': len(rows),
                          'wordBearingBoundaries': len(hypotheses), 'formsQueried': len(forms),
                          'recognizedJoinHypotheses': len(candidates), 'automaticChanges': 0},
              'candidates': candidates}
    target = store.root / ('boundary-scan-' + work_id + '.json')
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({'report': str(target), **report['summary']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    scan(Store(), sys.argv[1])
