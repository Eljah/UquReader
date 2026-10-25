import assert from 'node:assert/strict';
import {readFileSync, readdirSync, writeFileSync, mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
const read = p => JSON.parse(readFileSync(p, 'utf8'));
const root = '.codex/editorial/review';
const baseline = read(`${root}/snapshot-r941.json`);
const current = read(`${root}/snapshot.json`);
const plan = read('docs/editorial-review/gutter-restorations.json');
assert.equal(baseline.work.revision, plan.baselineRevision);
assert.equal(current.work.id, baseline.work.id);
const decisions = readdirSync(`${root}/gutter-results`).filter(f => f.endsWith('.json')).map(f => read(`${root}/gutter-results/${f}`));
assert.equal(decisions.length, plan.corrections.length);
const currentById = new Map(current.sentences.map(s => [s.id, s]));
const touched = new Set();
const changes = [];
for (const correction of plan.corrections) {
  const rows = correction.ordinals.map(n => baseline.sentences.find(s => s.ordinal === n));
  for (const row of rows) {
    assert(!touched.has(row.id), `Overlapping plan ${row.ordinal}`);
    touched.add(row.id);
  }
  const decision = decisions.find(d => d.sentenceId === rows[0].id);
  assert(decision?.saved, `Not saved: ${correction.ordinals}`);
  assert.deepEqual(decision.ordinals, correction.ordinals);
  assert.deepEqual(decision.replacements, correction.replacements);
  const before = rows.map(r => r.document.text).join('');
  let after = before;
  for (const [a, b] of correction.replacements) {
    assert.equal(after.split(a).length, 2, `Ambiguous replacement ${a}`);
    after = after.replace(a, b);
  }
  assert.equal(decision.before, before);
  assert.equal(decision.after, after);
  const survivor = currentById.get(rows[0].id);
  assert.equal(survivor?.document.text, after);
  if (before !== after) {
    assert(decision.runId, 'Changed text has no attested analysis');
    assert(decision.componentRunIds?.length, 'Missing sentence-level analysis provenance');
    assert.equal(decision.analysisParts.join(''), after);
  }
  const expectedRefs = [...new Map(rows.flatMap(r => r.document.sourceRefs).map(r => [JSON.stringify(r), r])).values()];
  assert.deepEqual(survivor.document.sourceRefs, expectedRefs);
  for (const consumed of rows.slice(1)) assert(!currentById.has(consumed.id), `Consumed record still active: ${consumed.ordinal}`);
  changes.push({...decision, currentRevision: survivor.revision,
    outcome: before === after ? 'already-matched-preferred-hypothesis' : 'saved-conjectural-restoration',
    sourceCertified: false});
}
let untouched = 0;
for (const row of baseline.sentences) if (!touched.has(row.id)) {
  assert.deepEqual(currentById.get(row.id)?.document, row.document, `Unrelated change: ${row.ordinal}`);
  untouched++;
}
for (const row of current.sentences) {
  const doc = row.document;
  assert.equal(doc.tokens.map(t => t.prefix + t.surface).join('') + doc.trailing, doc.text);
  let offset = 0;
  for (const token of doc.tokens) {
    offset += [...token.prefix].length;
    assert.equal(token.start, offset);
    offset += [...token.surface].length;
    assert.equal(token.end, offset);
  }
}
const text = current.sentences.map(s => s.document.text).join('');
const output = 'materials/mhr/elnet';
mkdirSync(output, {recursive:true});
const textFile = `${output}/elnet-r${current.work.revision}.txt`;
writeFileSync(textFile, text);
const report = {workId:current.work.id, baselineRevision:941, currentRevision:current.work.revision,
  requestedHypothesesApplied:true, sourceCertified:false, published:false,
  reviewedGroups:changes.length, changedGroups:changes.filter(c => c.before !== c.after).length,
  mergedRecords:baseline.sentences.length - current.sentences.length, untouchedDocumentsVerified:untouched,
  textFile, textSha256:createHash('sha256').update(text).digest('hex'),
  lowConfidenceGroups:changes.filter(c => c.confidence === 'low').map(c => c.ordinals), changes};
writeFileSync('docs/editorial-review/gutter-restoration-results.json', JSON.stringify(report, null, 2) + '\n');
writeFileSync(`${root}/publish-restorations.json`, JSON.stringify({name:'publish_work', arguments:{
  work_id:current.work.id, expected_revision:current.work.revision}}, null, 2));
console.log(JSON.stringify({...report, changes:undefined}));
