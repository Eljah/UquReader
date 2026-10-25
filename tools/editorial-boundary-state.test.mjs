import test from 'node:test';
import assert from 'node:assert/strict';
import {correctionIsApplied, applyLayoutOverrides, analysisSentences} from './editorial-boundary-state.mjs';
const current = {id:'first', document:{text:'Sentence.'}, merged_into:null};
test('sentence analysis retains blank paragraphs without blank requests', () => {
  const text = '\n\nFirst.\n\nSecond!  ';
  const parts = analysisSentences(text);
  assert.equal(parts.join(''), text);
  assert(parts.every(p => /\p{L}/u.test(p)));
  assert.equal(parts.length, 2);
  assert.throws(() => analysisSentences('\n\n . '), /No sentence/);
});
test('matching survivor text cannot conceal active trailing noise', () => {
  assert.equal(correctionIsApplied(current, 'Sentence.', [{id:'noise', merged_into:null}]), false);
});
test('completed merge requires all consumed records archived to survivor', () => {
  assert.equal(correctionIsApplied(current, 'Sentence.', [{merged_into:'first'}]), true);
  assert.equal(correctionIsApplied(current, 'Other.', [{merged_into:'first'}]), false);
  assert.throws(() => correctionIsApplied(current, 'Sentence.', [{merged_into:'other'}]));
});
test('single record edits and archived survivor', () => {
  assert.equal(correctionIsApplied(current, 'Sentence.', []), true);
  assert.throws(() => correctionIsApplied({...current,merged_into:'other'}, 'Sentence.', []));
});
test('restored chapter heading keeps offsets and unrelated tokens', () => {
  const doc = {tokens:[{start:2,surface:'5',role:'body'}, {start:5,surface:'Text',role:'body'}]};
  applyLayoutOverrides(doc, [{start:2,surface:'5',role:'heading',sourcePage:34,pageIndex:31}]);
  assert.deepEqual(doc.tokens, [{start:2,surface:'5',role:'heading',sourcePage:34,pageIndex:31},
    {start:5,surface:'Text',role:'body'}]);
  assert.throws(() => applyLayoutOverrides(doc, [{start:3,surface:'5',role:'heading'}]));
});
