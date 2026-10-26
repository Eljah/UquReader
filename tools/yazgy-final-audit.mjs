import {readFileSync,writeFileSync,readdirSync,existsSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const dir='docs/editorial-review',root='materials/tt/yazgy';
const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
const sourcePages=JSON.parse(readFileSync('.codex/editorial/yazgy/attached-sources.json','utf8')).pages;
const pagesBySource=new Map(sourcePages.map(p=>[p.sourceId,p.archiveImage]));
const reviewed=new Set(),changes=new Set(),passes=[];
let saveCount=0;
for(const name of readdirSync(dir).filter(n=>/^yazgy-.*\.json$/.test(n))){
 const plan=JSON.parse(readFileSync(`${dir}/${name}`,'utf8'));
 if(!Array.isArray(plan.changes))continue;
 const baselineRevision=plan.baselineRevision||813;
 const resultPath=`${dir}/${name.replace(/\.json$/,'-results.json')}`;
 const basePath=`${root}/yazgy-draft-r${baselineRevision}.jsonl`;
 if(!existsSync(resultPath)||!existsSync(basePath))continue;
 const base=readFileSync(basePath,'utf8').trim().split('\n').map(JSON.parse);
 const results=JSON.parse(readFileSync(resultPath,'utf8'));
 assert.equal(results.length,plan.changes.length,`Incomplete pass: ${name}`);
 for(const r of results){reviewed.add(r.sentenceId);if(r.saved){assert(r.runId);changes.add(r.sentenceId);saveCount++;}}
 for(const ordinal of plan.reviewedUnchangedOrdinals||[]){
  const row=base.find(r=>r.ordinal===ordinal);assert(row,`${name}: missing ordinal ${ordinal}`);reviewed.add(row.id);
 }
 passes.push({plan:name,baselineRevision,saved:results.filter(r=>r.saved).length});
}
const ids=new Set(),ordinals=new Set(),mixedScript=[],standaloneFolios=[],unreviewed=[],headings=[];
let tokens=0,footnotes=0;
for(const r of snapshot.sentences){
 assert(!ids.has(r.id));ids.add(r.id);assert(!ordinals.has(r.ordinal));ordinals.add(r.ordinal);
 assert(r.document.sourceRefs.length);
 for(const ref of r.document.sourceRefs){assert.equal(ref.page,1);assert(pagesBySource.has(ref.sourceId));}
 let text='',offset=0;
 for(const t of r.document.tokens){
  text+=t.prefix||'';offset+=[...(t.prefix||'')].length;assert.equal(t.start,offset);
  text+=t.surface;offset+=[...t.surface].length;assert.equal(t.end,offset);tokens++;
  if(t.role==='footnote')footnotes++;
  if(t.role==='heading')headings.push({ordinal:r.ordinal,surface:t.surface});
 }
 assert.equal(text+(r.document.trailing||''),r.document.text);
 for(const match of r.document.text.matchAll(/[\p{L}]+/gu))if(/\p{Script=Latin}/u.test(match[0])&&/\p{Script=Cyrillic}/u.test(match[0]))mixedScript.push({ordinal:r.ordinal,word:match[0]});
 if(/\n\s*(?:3[7-9]|[4-6][0-9]|7[0-3])\s*\n/.test(r.document.text))standaloneFolios.push(r.ordinal);
 if(!reviewed.has(r.id))unreviewed.push({id:r.id,ordinal:r.ordinal,pages:r.document.sourceRefs.map(s=>pagesBySource.get(s.sourceId)),text:r.document.text});
}
const text=snapshot.sentences.map(r=>r.document.text).join('');
assert(!mixedScript.length,JSON.stringify(mixedScript));assert(!standaloneFolios.length,JSON.stringify(standaloneFolios));
assert(footnotes>0);
assert.equal(unreviewed.length,0,'Every active unit must have a visual-review record');
assert.deepEqual(headings.map(h=>h.surface),['XXV','XXVI','XXVII','XXVIII','XXIX','XXX','XXXI']);
const report={workId:snapshot.work.id,revision:snapshot.work.revision,printedPages:{first:37,last:73,count:37},activeUnits:ids.size,tokens,footnoteTokens:footnotes,headings,attestedSaves:saveCount,distinctEditedIds:changes.size,passes,unreviewed,mixedScript,standaloneFolios,textSha256:createHash('sha256').update(text).digest('hex'),textTokenReconstructionVerified:true,sourceRefsVerified:true,firstVisualPagePassComplete:true,published:false,readerPaginationVerified:false};
if(existsSync(`${dir}/yazgy-publication.json`)){
 const publication=JSON.parse(readFileSync(`${dir}/yazgy-publication.json`,'utf8'));
 if(publication.workId===report.workId&&publication.revision===report.revision){report.published=true;report.publicationId=publication.id;}
}
writeFileSync(`${dir}/yazgy-final-audit.json`,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({...report,passes:passes.length,unreviewed:unreviewed.map(r=>({ordinal:r.ordinal,pages:r.pages,text:r.text.slice(0,150)}))}));
