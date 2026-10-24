import json
from import_existing import import_book
from store import Store

store = Store()
store.migrate()
print(json.dumps(import_book(store, '/opt/uqureader/assets/elnet_puncheryshte.vienna.ttmorph.jsonl',
    '\u042d\u043b\u043d\u0435\u0442 \u043f\u04f1\u043d\u0447\u0435\u0440\u044b\u0448\u0442\u0435', 'mhr',
    '/opt/uqureader/editor-data/import/drive-download.bin', '/opt/uqureader/editor-data/import/pdfjs-text.repaired.txt'), ensure_ascii=False), flush=True)
print(json.dumps(import_book(store, '/opt/uqureader/assets/yazgy_jillar.ttmorph.jsonl',
    '\u042f\u0437\u0433\u044b \u0497\u0438\u043b\u043b\u04d9\u0440', 'tt'), ensure_ascii=False), flush=True)
