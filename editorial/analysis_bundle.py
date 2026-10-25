"""Join independently analyzed sentences without changing their text or annotations."""
import copy


def combine_results(results):
    if not 1 <= len(results) <= 16:
        raise ValueError('Supply 1..16 analyzed sentences')
    text = ''.join(r['text'] for r in results)
    if not text.strip() or len(text) > 12000:
        raise ValueError('Combined text must contain 1..12000 characters')
    tokens, unknown = [], []
    offset, pending = 0, ''
    for result in results:
        base = len(tokens)
        for index, original in enumerate(result['tokens']):
            token = copy.deepcopy(original)
            token['start'] += offset
            token['end'] += offset
            if index == 0:
                token['prefix'] = pending + token.get('prefix', '')
            tokens.append(token)
        unknown.extend(base + index for index in result['unknownIndexes'])
        pending = result['trailing']
        offset += len(result['text'])
    if ''.join(t.get('prefix', '') + t['surface'] for t in tokens) + pending != text:
        raise ValueError('Combined token reconstruction mismatch')
    return dict(text=text, tokens=tokens, trailing=pending, unknownIndexes=unknown,
                wordCount=sum(r['wordCount'] for r in results),
                recognizedCount=sum(r['recognizedCount'] for r in results))
