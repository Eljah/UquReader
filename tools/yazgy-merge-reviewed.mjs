import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import assert from 'node:assert/strict';
import {connectEditor} from './editorial-client.mjs';
const [out,reason,...values]=process.argv.slice(2);
assert(out&&reason&&values.length>1,'Usage: output.json reason ordinal ordinal...');
assert(!existsSync(out),'Audit already exists; inspect it before retrying');
const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
const rows=values.map(n=>snapshot.sentences.find(r=>r.ordinal===Number(n)));
assert(rows.every(Boolean));
const c=await connectEditor();
try {
  for(const row of rows){const current=await c.call('get_sentence',{sentence_id:row.id});assert.equal(current.revision,row.revision);}
  const result=await c.call('merge_sentences',{sentence_ids:rows.map(r=>r.id),expected_revisions:rows.map(r=>r.revision),reason});
  const saved=await c.call('get_sentence',{sentence_id:rows[0].id});
  assert.equal(saved.document.text,rows.map(r=>r.document.text).join(''));
  writeFileSync(out,JSON.stringify({baselineRevision:snapshot.work.revision,ordinals:values.map(Number),...result,lossless:true},null,2)+'\n');
  console.log(JSON.stringify(result));
} finally {c.close();}
