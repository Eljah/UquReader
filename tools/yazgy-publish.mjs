import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {connectEditor} from './editorial-client.mjs';
const snapshot=JSON.parse(readFileSync('.codex/editorial/yazgy/snapshot.json','utf8'));
assert.equal(snapshot.work.revision,1298);
assert(snapshot.sentences.find(s=>s.ordinal===682).document.text.includes('Порканы'));
assert(snapshot.sentences.find(s=>s.ordinal===709).document.text.includes('җилбер сакаллы'));
const c=await connectEditor();
try {
 const publication=await c.call('publish_work',{work_id:snapshot.work.id,expected_revision:snapshot.work.revision});
 writeFileSync('docs/editorial-review/yazgy-publication.json',JSON.stringify(publication,null,2)+'\n');
 console.log(JSON.stringify(publication));
} finally {c.close();}
