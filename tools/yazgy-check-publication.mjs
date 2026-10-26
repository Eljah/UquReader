import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const publication=JSON.parse(readFileSync('docs/editorial-review/yazgy-publication.json','utf8'));
const rows=readFileSync(`materials/tt/yazgy/yazgy-draft-r${publication.revision}.jsonl`,'utf8').trim().split('\n').map(JSON.parse);
const expected=rows.flatMap(r=>r.document.tokens);
const base='https://uqureader.tatar';
async function get(path){const r=await fetch(base+path,{signal:AbortSignal.timeout(30000)});assert.equal(r.status,200);return r.json();}
const work=(await get('/api/works')).works.find(w=>w.id===publication.id);
assert(work);assert.equal(work.tokenCount,expected.length);
const received=[];
for(let page=0;received.length<expected.length;page++){
 const result=await get(`/api/works/${publication.id}/tokens?page=${page}&pageSize=2000`);
 assert(result.tokens.length,'Unexpected empty page');
 received.push(...result.tokens);
}
assert.equal(received.length,expected.length);
for(let i=0;i<expected.length;i++){
 assert.equal(received[i].surface,expected[i].surface,`Token ${i}`);
 assert.equal(received[i].prefix||'',expected[i].prefix||'',`Prefix ${i}`);
}
const text=received.map(t=>(t.prefix||'')+t.surface).join('');
assert(text.includes('Порканы'));assert(text.includes('җилбер сакаллы'));
assert(!text.includes('Йорканны'));assert(!text.includes('җибәр сакаллы'));
const result={publicationId:publication.id,revision:publication.revision,url:base,checkedTokens:received.length,allSurfacesAndPrefixesMatch:true,userCorrectionsVisible:true,sourcePaged:work.sourcePaged};
writeFileSync('docs/editorial-review/yazgy-publication-verification.json',JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));
