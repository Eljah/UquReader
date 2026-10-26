import{readFileSync,writeFileSync}from'node:fs';
import assert from'node:assert/strict';
const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
const sources=JSON.parse(readFileSync('.codex/editorial/yazgy/attached-sources.json','utf8')).pages;
const sourceIds=new Set(sources.map(s=>s.sourceId));
assert.equal(new Set(snapshot.sentences.map(r=>r.id)).size,snapshot.sentences.length);
assert.equal(new Set(snapshot.sentences.map(r=>r.ordinal)).size,snapshot.sentences.length);
for(const row of snapshot.sentences){
  let text='',cursor=0;
  for(const token of row.document.tokens){
    cursor+=[...(token.prefix||'')].length;assert.equal(token.start,cursor);
    cursor+=[...token.surface].length;assert.equal(token.end,cursor);
    text+=(token.prefix||'')+token.surface;
  }
  assert.equal(text+(row.document.trailing||''),row.document.text);
  assert.ok(row.document.sourceRefs.length);
  for(const ref of row.document.sourceRefs)assert.ok(ref.page===1&&sourceIds.has(ref.sourceId));
}
const base=`materials/tt/yazgy/yazgy-draft-r${snapshot.work.revision}`;
writeFileSync(base+'.jsonl',snapshot.sentences.map(r=>JSON.stringify(r)).join('\n')+'\n');
writeFileSync(base+'.txt',snapshot.sentences.map(r=>r.document.text).join(''));
console.log(JSON.stringify({revision:snapshot.work.revision,records:snapshot.sentences.length,reconstructionVerified:true,sourceRefsVerified:true}));
