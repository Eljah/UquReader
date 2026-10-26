import {readFileSync,writeFileSync,readdirSync} from 'node:fs';
import assert from 'node:assert/strict';
const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
assert.equal(snapshot.work.revision,1274);
const dir='docs/editorial-review';
const plans=readdirSync(dir).filter(n=>/^yazgy-.*\.json$/.test(n)).map(n=>JSON.parse(readFileSync(`${dir}/${n}`,'utf8'))).filter(p=>Array.isArray(p.changes));
const fixes=new Map([
 [100,[['инҗир','инжир']]],
 [221,[['Шуйла ки','Шуйлә ки']]],
 [223,[['вазифадыр','вазыйфадыр']]],
 [434,[['чәрдәкләнеп','чәрпәкләнеп']]],
 [591,[['Сәрбикамал','Сәрбиҗамал'],['тәҗвитләрен','тәштитләрен']]],
]);
const changes=[];
for(const row of snapshot.sentences){
 const replacements=[...(fixes.get(row.ordinal)||[])];
 if(row.document.text.includes('Бикләмшев'))replacements.push(['Бикләмшев','Бикләмишев']);
 if(!replacements.length)continue;
 const candidates=plans.flatMap(p=>p.changes).filter(e=>e.sentences?.join('')===row.document.text);
 assert(candidates.length,`No attested sentence boundaries for ${row.ordinal}`);
 const edit=structuredClone(candidates.at(-1));
 edit.ordinal=row.ordinal;
 for(const [from,to] of replacements){
  assert(edit.sentences.some(s=>s.includes(from)));
  edit.sentences=edit.sentences.map(s=>s.replaceAll(from,to));
 }
 edit.reason=`Second visual review of magnified source pages ${edit.pages.join('/')}: ${replacements.map(([a,b])=>`${a} -> ${b}`).join('; ')}. Correct first-pass transcription, not modernization.`;
 changes.push(edit);
}
const first=snapshot.sentences.find(r=>r.ordinal===0);
changes.unshift({ordinal:0,pages:[37],headingSurfaces:['XXV'],sentences:['\n\nXXV\n\n'+first.document.text.trimStart()],reason:'Magnified scan 37: restore printed chapter XXV heading, previously omitted; body text unchanged.'});
const plan={workId:snapshot.work.id,baselineRevision:1274,reviewedUnchangedOrdinals:[],nextReview:'Complete first visual pass through pages 37-73. Final integrity audit and documented low-resolution uncertainties; no publication.',changes};
writeFileSync(`${dir}/yazgy-final-review-pass2.json`,JSON.stringify(plan,null,2)+'\n');
console.log(JSON.stringify({changes:changes.length,ordinals:changes.map(e=>e.ordinal)}));
