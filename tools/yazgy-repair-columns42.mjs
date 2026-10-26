import{readFileSync,writeFileSync,existsSync}from'node:fs';
import assert from'node:assert/strict';
import{connectEditor}from'./editorial-client.mjs';
const path='docs/editorial-review/yazgy-column42-results.json';
const baseline=readFileSync('materials/tt/yazgy/yazgy-draft-r870.jsonl','utf8').trim().split('\n').map(JSON.parse);
const workId='fea266e5c60a4747898024bd321321ca';
const checkpoint=existsSync(path)?JSON.parse(readFileSync(path,'utf8')):{workId,baselineRevision:870,splits:[],merges:[]};
const persist=()=>writeFileSync(path,JSON.stringify(checkpoint,null,2)+'\n');
const c=await connectEditor();
try{
  for(const [ordinal,count]of [[97,12],[121,46],[132,20]]){
    if(checkpoint.splits.some(s=>s.originalOrdinal===ordinal))continue;
    const original=baseline.find(r=>r.ordinal===ordinal);
    const current=await c.call('get_sentence',{sentence_id:original.id});
    assert.equal(current.revision,original.revision);
    const split=await c.call('split_sentence',{sentence_id:current.id,expected_revision:current.revision,after_token:count,reason:'Scans 41-43: isolate column-boundary fragments on page 42 before correcting reversed column order; lossless split.'});
    const first=await c.call('get_sentence',{sentence_id:split.sentenceId});
    const next=await c.call('get_sentence',{sentence_id:split.nextSentenceId});
    assert.equal(first.document.text+next.document.text,current.document.text);
    checkpoint.splits.push({originalOrdinal:ordinal,...split});persist();
  }
  const a=checkpoint.splits.find(s=>s.originalOrdinal===97), b=checkpoint.splits.find(s=>s.originalOrdinal===121), d=checkpoint.splits.find(s=>s.originalOrdinal===132);
  const originalId=n=>baseline.find(r=>r.ordinal===n).id;
  const desired=[a.sentenceId,b.nextSentenceId,...baseline.filter(r=>r.ordinal>=122&&r.ordinal<=131).map(r=>r.id),d.sentenceId,a.nextSentenceId,...baseline.filter(r=>r.ordinal>=98&&r.ordinal<=120).map(r=>r.id),b.sentenceId,d.nextSentenceId];
  if(!checkpoint.reorder){
    const rows=[];for(const id of desired)rows.push(await c.call('get_sentence',{sentence_id:id}));
    const work=(await c.call('list_works')).works.find(w=>w.id===workId);
    const args={work_id:workId,expected_work_revision:work.revision,sentence_ids:desired,expected_revisions:rows.map(r=>r.revision),reason:'Scan 42: read LEFT column before RIGHT column. Preserve page-41 lead-in and page-43 continuation. No document changes in this operation.'};
    const preview=await c.call('reorder_sentences',{...args,dry_run:true});assert.equal(preview.valid,true);
    const result=await c.call('reorder_sentences',args);assert.equal(result.saved,true);
    for(const old of rows){const current=await c.call('get_sentence',{sentence_id:old.id});assert.deepEqual(current.document,old.document);}
    checkpoint.reorder={...result,desiredOrder:desired,documentsUnchanged:true};persist();
  }
  for(const ids of [[a.sentenceId,b.nextSentenceId],[d.sentenceId,a.nextSentenceId]]){
    if(checkpoint.merges.some(m=>m.sentenceId===ids[0]))continue;
    const rows=[];for(const id of ids)rows.push(await c.call('get_sentence',{sentence_id:id}));
    const merged=await c.call('merge_sentences',{sentence_ids:ids,expected_revisions:rows.map(r=>r.revision),reason:'Scan 42: reunite sentence fragments after column-order repair; no text changed in merge; separate attested correction follows.'});
    const after=await c.call('get_sentence',{sentence_id:ids[0]});assert.equal(after.document.text,rows.map(r=>r.document.text).join(''));
    checkpoint.merges.push(merged);persist();
  }
  console.log(JSON.stringify({splits:checkpoint.splits.length,reordered:checkpoint.reorder.moves.length,merges:checkpoint.merges.length}));
}finally{c.close();}
