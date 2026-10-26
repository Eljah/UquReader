import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {connectEditor} from './editorial-client.mjs';

const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
const client=await connectEditor();
const report={workId:snapshot.work.id,revision:snapshot.work.revision,checked:0,failures:[]};
const unwrap=r=>r.result??r;
async function checkRevision(){
 const works=unwrap(await client.call('list_works')).works;
 assert.equal(works.find(w=>w.id===report.workId)?.revision,report.revision,'Draft changed during verification');
}
try {
 await checkRevision();
 for(const row of snapshot.sentences){
  const result=unwrap(await client.call('validate_sentence',{
   sentence_id:row.id,expected_revision:row.revision,document:row.document,
   reason:'Final read-only validation of visually reviewed Yazgy draft; no save requested.'
  }));
  report.checked++;
  if(!result.valid||result.saved)report.failures.push({id:row.id,ordinal:row.ordinal,result});
  if(report.checked%100===0)console.log(`Validated ${report.checked}/${snapshot.sentences.length}`);
 }
 await checkRevision();
 report.complete=report.checked===snapshot.sentences.length;
 writeFileSync('docs/editorial-review/yazgy-final-server-validation.json',JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify(report));
 assert.equal(report.failures.length,0);
} finally {client.close();}
