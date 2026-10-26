import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const root='docs/editorial-review';
const baseline=readFileSync('materials/tt/yazgy/yazgy-draft-r1296.jsonl','utf8').trim().split('\n').map(JSON.parse);
const changes=[];
for(const [page,ordinal,from,to] of [[66,682,'Йорканны','Порканы'],[67,709,'җибәр сакаллы','җилбер сакаллы']]){
 const plan=JSON.parse(readFileSync(`${root}/yazgy-page${page}-pass1.json`,'utf8'));
 const edit=structuredClone(plan.changes.find(e=>e.ordinal===ordinal));
 assert.equal(edit.sentences.join(''),baseline.find(r=>r.ordinal===ordinal).document.text);
 assert(edit.sentences.join('').includes(from));
 edit.sentences=edit.sentences.map(s=>s.replaceAll(from,to));
 edit.reason=`User-confirmed source reading on printed page ${page}: ${from} -> ${to}. Preserve all other text and sentence boundaries.`;
 changes.push(edit);
}
writeFileSync(`${root}/yazgy-user-confirmed.json`,JSON.stringify({workId:'fea266e5c60a4747898024bd321321ca',baselineRevision:1296,reviewedUnchangedOrdinals:[],nextReview:'Publish user-confirmed corrections; retain unresolved chant on page 61.',changes},null,2)+'\n');
