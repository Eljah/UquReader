import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { applyTokenEdits } from './editorial-token-edits.mjs';
const root = '.codex/editorial/review';
const read = path => JSON.parse(readFileSync(path, 'utf8'));
const snapshot = read(`${root}/snapshot-r64.json`);
const candidates = read(`${root}/candidate-review/candidates.json`);
const notes = read('docs/editorial-review/join-review-notes.json');
const resolutions = read('docs/editorial-review/join-resolutions.json');
assert.equal(snapshot.work.revision, notes.baselineRevision);
assert.equal(candidates.length, 522);
const rejected = {...notes.reject, ...notes.additionalReject, ...notes.finalReject, ...resolutions.reject};
const overrides = new Map();
for (const [ids, from, to, replacement] of resolutions.edits) {
  for (const id of ids) {
    assert(!overrides.has(id));
    overrides.set(id, {ids, from, to, replacement});
  }
}
const requiresResolution = new Set([...notes.inspectExpanded, ...notes.additionalInspect, ...notes.finalInspect]);
const ledger = [], groups = new Map();
for (const c of candidates) {
  assert(notes.reviewedSheets.includes(Math.ceil(c.id / 16)));
  const original=snapshot.sentences.find(s=>s.id===c.sentenceId);
  assert.equal(original?.revision,c.revision,`Candidate revision mismatch: ${c.id}`);
  assert.equal(original.document.tokens[c.fromToken]?.surface,c.left);
  assert.equal(original.document.tokens[c.toToken]?.surface,c.right);
  const entry = {id:c.id, sentenceId:c.sentenceId, ordinal:c.ordinal, page:c.location.page,
    proposed:c.joined, sourceRefs:c.sourceRefs, visualReviewed:true, sheet:Math.ceil(c.id/16)};
  if (rejected[c.id]) { entry.status='rejected'; entry.reason=rejected[c.id]; }
  else if (resolutions.unresolved[c.id]) { entry.status='unresolved'; entry.reason=resolutions.unresolved[c.id]; }
  else {
    assert(!requiresResolution.has(c.id) || overrides.has(c.id), `Unresolved decision ${c.id}`);
    const edit = overrides.get(c.id) ?? {ids:[c.id],from:c.fromToken,to:c.toToken,replacement:c.joined};
    assert(edit.ids.every(id=>candidates.find(c=>c.id===id)?.sentenceId===c.sentenceId));
    entry.status='accepted'; entry.edit=edit;
    if (!groups.has(c.sentenceId)) groups.set(c.sentenceId, {candidateIds:[], edits:[]});
    const group=groups.get(c.sentenceId);
    group.candidateIds.push(c.id);
    if (!group.edits.some(e=>e.ids[0]===edit.ids[0])) group.edits.push(edit);
  }
  ledger.push(entry);
}
const plans=[];
for (const [id,group] of groups) {
  const s=snapshot.sentences.find(s=>s.id===id);
  const {after,edits}=applyTokenEdits(s.document,group.edits);
  assert.notEqual(after,s.document.text);
  plans.push({sentenceId:id,ordinal:s.ordinal,revision:s.revision,before:s.document.text,after,
    candidateIds:group.candidateIds,edits,sourceRefs:s.document.sourceRefs});
}
mkdirSync(`${root}/joins`,{recursive:true});
writeFileSync(`${root}/joins/plan.json`,JSON.stringify(plans,null,2));
writeFileSync('docs/editorial-review/join-candidates.json',JSON.stringify(ledger,null,2)+'\n');
console.log(JSON.stringify({candidates:ledger.length,accepted:ledger.filter(e=>e.status==='accepted').length,
  rejected:ledger.filter(e=>e.status==='rejected').length,unresolved:ledger.filter(e=>e.status==='unresolved').length,
  sentences:plans.length,edits:plans.reduce((n,p)=>n+p.edits.length,0)}));
