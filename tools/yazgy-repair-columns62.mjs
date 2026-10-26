import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import assert from 'node:assert/strict';
import {connectEditor} from './editorial-client.mjs';
const file='docs/editorial-review/yazgy-column62-results.json';
const baseline=readFileSync('materials/tt/yazgy/yazgy-draft-r1119.jsonl','utf8').trim().split('\n').map(JSON.parse);
const workId='fea266e5c60a4747898024bd321321ca';
const audit=existsSync(file)?JSON.parse(readFileSync(file,'utf8')):{workId,baselineRevision:1119,splits:[],merges:[]};
const persist=()=>writeFileSync(file,JSON.stringify(audit,null,2)+'\n');
const c=await connectEditor();
try {
 for(const [ordinal,count] of [[591,111],[604,9],[618,15]]) {
  if(audit.splits.some(s=>s.originalOrdinal===ordinal))continue;
  const old=baseline.find(r=>r.ordinal===ordinal);
  const current=await c.call('get_sentence',{sentence_id:old.id});assert.equal(current.revision,old.revision);
  const split=await c.call('split_sentence',{sentence_id:old.id,expected_revision:old.revision,after_token:count,reason:'Magnified scans 61-63: isolate reversed column fragments on page 62; lossless split before source-attested correction.'});
  const first=await c.call('get_sentence',{sentence_id:split.sentenceId});
  const next=await c.call('get_sentence',{sentence_id:split.nextSentenceId});
  assert.equal(first.document.text+next.document.text,old.document.text);
  audit.splits.push({originalOrdinal:ordinal,...split});persist();
 }
 const a=audit.splits.find(s=>s.originalOrdinal===591),b=audit.splits.find(s=>s.originalOrdinal===604),d=audit.splits.find(s=>s.originalOrdinal===618);
 const range=(lo,hi)=>baseline.filter(r=>r.ordinal>=lo&&r.ordinal<=hi).map(r=>r.id);
 const desired=[a.sentenceId,b.nextSentenceId,...range(605,617),d.sentenceId,a.nextSentenceId,...range(592,603),b.sentenceId,d.nextSentenceId];
 if(!audit.reorder){
  const rows=[];for(const id of desired)rows.push(await c.call('get_sentence',{sentence_id:id}));
  const work=(await c.call('list_works')).works.find(w=>w.id===workId);
  const args={work_id:workId,expected_work_revision:work.revision,sentence_ids:desired,expected_revisions:rows.map(r=>r.revision),reason:'Magnified scan 62: restore LEFT-before-RIGHT column order while preserving page-61 lead-in and page-63 continuation; documents unchanged.'};
  assert.equal((await c.call('reorder_sentences',{...args,dry_run:true})).valid,true);
  const result=await c.call('reorder_sentences',args);assert.equal(result.saved,true);
  for(const old of rows)assert.deepEqual((await c.call('get_sentence',{sentence_id:old.id})).document,old.document);
  audit.reorder={...result,desiredOrder:desired,documentsUnchanged:true};persist();
 }
 for(const ids of [[a.sentenceId,b.nextSentenceId],[d.sentenceId,a.nextSentenceId],[b.sentenceId,d.nextSentenceId]]){
  if(audit.merges.some(m=>m.sentenceId===ids[0]))continue;
  const rows=[];for(const id of ids)rows.push(await c.call('get_sentence',{sentence_id:id}));
  const result=await c.call('merge_sentences',{sentence_ids:ids,expected_revisions:rows.map(r=>r.revision),reason:'Scans 61-63: reunite fragments after page-62 column reorder; lossless merge, attested text correction follows.'});
  assert.equal((await c.call('get_sentence',{sentence_id:ids[0]})).document.text,rows.map(r=>r.document.text).join(''));
  audit.merges.push(result);persist();
 }
 console.log(JSON.stringify({splits:audit.splits.length,moves:audit.reorder.moves.length,merges:audit.merges.length}));
}finally{c.close();}
