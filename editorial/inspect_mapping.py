import json
import re
from collections import Counter
from pathlib import Path

records = [json.loads(l) for l in Path('/opt/uqureader/assets/elnet_puncheryshte.vienna.ttmorph.jsonl').read_text().splitlines() if l.strip()]
pages = Path('/opt/uqureader/editor-data/import/pdfjs-text.repaired.txt').read_text().split('\f')
words = lambda text: Counter(re.findall(r'[^\W\d_]+', text.casefold()))
pw = [words(p) for p in pages]
for i in range(4):
    rows = [r for r in records if r['pageIndex'] == i]
    sample = words(' '.join(r['surface'] for r in rows))
    scores = sorted([(sum((sample & p).values()) / max(1, sum(sample.values())), j + 1) for j, p in enumerate(pw)], reverse=True)[:4]
    print(i, len(rows), scores, ' '.join(r['surface'] for r in rows[:15]))
for i in range(4):
    print('PDF', i+1, repr(pages[i][:300]))
