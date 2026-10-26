import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const root='materials/tt/yazgy';
const sources=JSON.parse(readFileSync('.codex/editorial/yazgy/attached-sources.json','utf8')).pages;
const planPath=process.argv[2]||'docs/editorial-review/yazgy-page37-pass2.json';
const plan=JSON.parse(readFileSync(planPath,'utf8'));
const baselineRevision=plan.baselineRevision||813;
const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
const before=readFileSync(`${root}/yazgy-draft-r${baselineRevision}.jsonl`,'utf8').trim().split('\n').map(JSON.parse);
const results=JSON.parse(readFileSync(planPath.replace(/\.json$/,'-results.json'),'utf8'));
const edits=new Map(results.filter(r=>r.saved).map(r=>[r.sentenceId,r]));
assert.equal(snapshot.sentences.length,before.length);
let unchanged=0,footnoteTokens=0,transferredTranslationsVerified=0,sourceRefsUnchanged=true;
for(const old of before){
  const current=snapshot.sentences.find(r=>r.id===old.id);
  assert.ok(current);
  assert.equal(current.ordinal,old.ordinal);
  const edit=edits.get(old.id);
  if(!edit){assert.deepEqual(current.document,old.document);unchanged++;}
  else {
    assert.equal(current.document.text,edit.after);
    assert.equal(current.revision,edit.revision);
    const change=plan.changes.find(r=>r.ordinal===old.ordinal);
    assert.deepEqual(current.document.sourceRefs,change.pages.map(p=>({sourceId:sources.find(s=>s.archiveImage===p).sourceId,page:1})));
    if(JSON.stringify(current.document.sourceRefs)!==JSON.stringify(old.document.sourceRefs))sourceRefsUnchanged=false;
    for(const donorOrdinal of change.translationSourceOrdinals||[]){
      const donor=before.find(r=>r.ordinal===donorOrdinal);
      assert.ok(donor);
      for(const token of current.document.tokens){
        for(const prior of donor.document.tokens.filter(t=>t.surface===token.surface&&t.analysis===token.analysis)){
          for(const translation of prior.translations||[]){
            assert.ok(token.translations?.includes(translation));
            transferredTranslationsVerified++;
          }
        }
      }
    }
  }
  let position=0,text='';
  for(const t of current.document.tokens){
    position += [...(t.prefix||'')].length;
    assert.equal(t.start,position);
    position += [...t.surface].length;
    assert.equal(t.end,position);
    text+=(t.prefix||'')+t.surface;
    if(t.role==='footnote'){assert.ok(t.footnoteId);footnoteTokens++;}
  }
  assert.equal(text+(current.document.trailing||''),current.document.text);
}
assert.equal(results.length,plan.changes.length);
assert.ok(footnoteTokens>0);
writeFileSync(`${root}/yazgy-draft-r${snapshot.work.revision}.jsonl`,snapshot.sentences.map(r=>JSON.stringify(r)).join('\n')+'\n');
writeFileSync(`${root}/yazgy-draft-r${snapshot.work.revision}.txt`,snapshot.sentences.map(r=>r.document.text).join(''));
const report={revision:snapshot.work.revision,baselineRevision,changedUnits:edits.size,unchangedUnits:unchanged,
  stableIdsAndOrdinals:true,sourceRefsUnchanged,sourceRefsVerified:true,tokenReconstructionVerified:true,footnoteTokens,
  transferredTranslationsVerified,reviewedUnchangedOrdinals:plan.reviewedUnchangedOrdinals||[],
  published:false,fullProofreadingComplete:false,nextReview:plan.nextReview||'Page 38 after the opening page-spanning unit; pages 42 and 62 still require column-order restoration.'};
writeFileSync(planPath.replace(/\.json$/,'-verification.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
