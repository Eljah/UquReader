import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyTokenEdits } from './editorial-token-edits.mjs';

function document(text, surfaces) {
  const chars=Array.from(text); let cursor=0;
  return {text, tokens:surfaces.map(surface=>{
    while(cursor < chars.length && chars[cursor] !== Array.from(surface)[0]) cursor++;
    assert(cursor < chars.length);
    const token={surface,start:cursor,end:cursor+Array.from(surface).length,pageIndex:cursor<4?0:1};
    cursor=token.end; return token;
  })};
}
test('Only selected occurrence changes, punctuation and other text survive',()=>{
  const doc=document('ab cd, ab cd.', ['ab','cd',',','ab','cd','.']);
  assert.equal(applyTokenEdits(doc,[{from:3,to:4,replacement:'abcd'}]).after,'ab cd, abcd.');
});
test('Unicode code point offsets and subsequent edit positions',()=>{
  const doc=document('\u{1F600} a b c d!', ['a','b','c','d','!']);
  const result=applyTokenEdits(doc,[{from:0,to:1,replacement:'ab'},{from:2,to:3,replacement:'cd'}]);
  assert.equal(result.after,'\u{1F600} ab cd!');
  assert.deepEqual(result.edits.map(e=>[e.newStart,e.newEnd]),[[2,4],[5,7]]);
});
test('Cross-page join removes line hyphen and retains first-fragment page',()=>{
  const result=applyTokenEdits(document('ab-\ncd', ['ab','-','cd']),[{from:0,to:2,replacement:'abcd'}]);
  assert.equal(result.after,'abcd'); assert.equal(result.edits[0].layout.pageIndex,0);
});
test('Overlapping and out-of-bounds edits fail closed',()=>{
  const doc=document('a b c',['a','b','c']);
  assert.throws(()=>applyTokenEdits(doc,[{from:0,to:1,replacement:'ab'},{from:1,to:2,replacement:'bc'}]),/Overlapping/);
  assert.throws(()=>applyTokenEdits(doc,[{from:0,to:3,replacement:'abc'}]));
});
