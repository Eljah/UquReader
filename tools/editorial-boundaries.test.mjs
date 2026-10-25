import {test} from 'node:test';
import assert from 'node:assert/strict';
import {scanBoundaries} from './editorial-boundaries.mjs';

function snapshot(texts) {
  return {work: {id: 'book', revision: 7}, sentences: texts.map((text, ordinal) => ({
    id: String(ordinal), ordinal, revision: 1,
    document: {text, sourceRefs: [{sourceId: 'pdf', page: ordinal + 1}],
      tokens: text.split(/\s+/).filter(Boolean).map(surface => ({surface, analysis: 'Known'}))},
  }))};
}
test('inventories every adjacent pair, including fully recognized words', () => {
  const input = snapshot(['One.', 'Two.', 'Three.']);
  const before = JSON.stringify(input);
  const result = scanBoundaries(input);
  assert.equal(result.boundaries.length, 2);
  assert.deepEqual(result.boundaries[1].pair, ['1', '2']);
  assert.equal(JSON.stringify(input), before);
  assert(result.boundaries.every(b => !b.visualReviewed && b.status === 'pending'));
});
test('punctuation-only records do not conceal the following word', () => {
  const result = scanBoundaries(snapshot(['Тудо адакшым.', '!', ' пеш тыматле.']));
  assert.equal(result.summary.expandedWindows, 2);
  for (const b of result.boundaries) {
    assert.equal(b.window.length, 3);
    assert.equal(b.sourceRefs.length, 3);
    assert(b.reasons.includes('punctuation-only-record'));
    assert.equal(b.status, 'pending');
  }
});
test('passenger split is flagged without requiring an unknown analysis', () => {
  const b = scanBoundaries(snapshot(['Казань велке пассажир.', ') екий поезд чыма.'])).boundaries[0];
  assert(b.reasons.includes('suspicious-punctuation-sequence'));
  assert(b.reasons.includes('leading-punctuation'));
  assert.equal(b.window[1].text, ') екий поезд чыма.');
});
test('no invented boundary for an empty or one-record work', () => {
  assert.equal(scanBoundaries(snapshot([])).summary.boundaries, 0);
  assert.equal(scanBoundaries(snapshot(['Only.'])).summary.boundaries, 0);
});
test('orphan closing bracket flags the Lida context for visual review', () => {
  const r = scanBoundaries(snapshot(['Неужели Лида?', ' Кузе тудо) тыгай пашаеҥ лийын шуктен?']));
  assert.equal(r.summary.unmatchedBrackets, 1);
  assert(r.boundaries[0].reasons.includes('unmatched-closing-bracket'));
  assert.equal(r.bracketIssues[0].sentenceId, '1');
  assert.equal(r.boundaries[0].status, 'pending');
});
test('valid bracket pairs across records are not treated as orphan brackets', () => {
  const r = scanBoundaries(snapshot(['(First.', 'Second.)']));
  assert.equal(r.summary.unmatchedBrackets, 0);
});
test('offsets are Unicode code points and unmatched brackets survive a single-record scan', () => {
  const r = scanBoundaries(snapshot(['\u{1F600} )']));
  assert.equal(r.bracketIssues[0].offset, 2);
  assert.equal(r.summary.unmatchedBrackets, 1);
});
