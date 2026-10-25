import assert from 'node:assert/strict';

export function applyTokenEdits(document, requested) {
  const chars = Array.from(document.text);
  const edits = [...requested].sort((a,b) => a.from-b.from).map(e => {
    assert(Number.isInteger(e.from) && Number.isInteger(e.to));
    assert(e.from >= 0 && e.to >= e.from && e.to < document.tokens.length);
    assert(typeof e.replacement === 'string' && e.replacement.length > 0);
    const first = document.tokens[e.from], last = document.tokens[e.to];
    assert.equal(chars.slice(first.start, first.end).join(''), first.surface);
    assert.equal(chars.slice(last.start, last.end).join(''), last.surface);
    return {...e, start:first.start, end:last.end, before:chars.slice(first.start,last.end).join(''),
      layout:Object.fromEntries(['role','pageIndex','sourcePage','footnoteId']
        .filter(k => k in first).map(k => [k,first[k]]))};
  });
  for (let i=1;i<edits.length;i++) assert(edits[i].start >= edits[i-1].end, 'Overlapping edits');
  let after = '', cursor = 0;
  for (const e of edits) {
    after += chars.slice(cursor,e.start).join('');
    e.newStart = Array.from(after).length;
    after += e.replacement;
    e.newEnd = Array.from(after).length;
    cursor = e.end;
  }
  after += chars.slice(cursor).join('');
  return {after, edits};
}
