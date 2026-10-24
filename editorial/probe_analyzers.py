import json
from analyzers import analyze

for language, analyzer, text in [('mhr', 'vienna', '\u0428\u043e\u0448\u043e.'),
                                  ('mhr', 'giella-hfst', '\u0428\u043e\u0448\u043e.'),
                                  ('tt', 'uqureader-tatar', '\u0411\u0430\u043b\u0430 \u043a\u0438\u043b\u04d9.')]:
    try:
        print(json.dumps({'analyzer': analyzer, **analyze(language, analyzer, text)}, ensure_ascii=False), flush=True)
    except Exception as error:
        print(analyzer, type(error).__name__, str(error), flush=True)
