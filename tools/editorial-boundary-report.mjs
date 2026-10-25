import {readFileSync, readdirSync, writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {scanBoundaries} from './editorial-boundaries.mjs';
import {auditWindow} from './editorial-priority-audit.mjs';
const read = p => JSON.parse(readFileSync(p, 'utf8'));
const root = '.codex/editorial/review';
const baseline = read(`${root}/snapshot-r475.json`);
const current = read(`${root}/snapshot.json`);
const initial = read(`${root}/boundaries.json`);
const decisions = readdirSync(`${root}/boundary-results`).filter(f=>f.endsWith('.json')).map(f=>read(`${root}/boundary-results/${f}`));
const followupPlans = read('docs/editorial-review/boundary-followups.json');
const followups = readdirSync(`${root}/boundary-followup-results`).filter(f=>f.endsWith('.json')).map(f=>read(`${root}/boundary-followup-results/${f}`));
assert.equal(followups.length, followupPlans.length);
const supersededIds = new Set();
for (const plan of followupPlans) {
  const saved = followups.find(d => d.ordinals[0] === plan.ordinals[0]);
  assert(saved?.saved && saved.merge?.saved, 'Incomplete follow-up merge');
  assert.deepEqual(saved.ordinals, plan.ordinals);
  assert.deepEqual(saved.replacements, plan.replacements);
  const expectedBefore = plan.ordinals.map(ordinal => {
    const row = baseline.sentences.find(s => s.ordinal === ordinal);
    assert(!supersededIds.has(row.id), 'Overlapping follow-ups');
    supersededIds.add(row.id);
    return decisions.find(d => d.sentenceId === row.id)?.after ?? row.document.text;
  }).join('');
  assert.equal(saved.before, expectedBefore, 'Follow-up lost previous corrections');
  let expectedAfter = expectedBefore;
  for (const [before, after] of plan.replacements) {
    assert.equal(expectedAfter.split(before).length, 2, 'Ambiguous follow-up');
    expectedAfter = expectedAfter.replace(before, after);
  }
  assert.equal(saved.after, expectedAfter);
}
const plans = read('docs/editorial-review/boundary-corrections.json');
const plannedOrdinals = new Set();
for (const plan of plans) {
  for (const ordinal of plan.ordinals) {
    assert(!plannedOrdinals.has(ordinal), `Overlapping correction ${ordinal}`);
    plannedOrdinals.add(ordinal);
  }
  const saved = decisions.find(d => d.ordinals[0] === plan.ordinals[0]);
  assert(saved?.saved, `Missing saved plan ${plan.ordinals}`);
  assert.deepEqual(saved.ordinals, plan.ordinals);
  // Earlier checkpoints can contain no-op replacements; compare effective edits.
  const effective = replacements => replacements.filter(([before,after])=>before!==after);
  assert.deepEqual(effective(saved.replacements), effective(plan.replacements));
}
const notes = read('docs/editorial-review/bracket-unresolved.json');
const verifiedUnchanged = read('docs/editorial-review/boundary-unchanged.json');
assert.equal(verifiedUnchanged.baselineRevision, baseline.work.revision);
for (const decision of verifiedUnchanged.decisions) {
  assert.equal(decision.status, 'verified-unchanged');
  assert(decision.evidence && decision.page && decision.ordinals.length);
  for (const ordinal of decision.ordinals) {
    assert(baseline.sentences.some(s => s.ordinal === ordinal), `Unknown reviewed ordinal ${ordinal}`);
  }
}
const byId = new Map(current.sentences.map(s=>[s.id,s]));
const touched = new Set();
for (const d of [...decisions, ...followups]) {
  assert(d.saved, `Unfinished correction ${d.ordinals}`);
  if (!supersededIds.has(d.sentenceId) || followups.includes(d)) {
    assert.equal(byId.get(d.sentenceId)?.document.text,d.after);
  }
  for (const override of d.layoutOverrides || []) {
    const token = byId.get(d.sentenceId).document.tokens.find(t => t.start === override.start && t.surface === override.surface);
    assert(token, 'Missing layout override token');
    for (const key of ['role', 'sourcePage', 'pageIndex', 'footnoteId']) {
      if (Object.hasOwn(override, key)) assert.equal(token[key], override[key]);
    }
  }
  for (const ordinal of d.ordinals.slice(1)) {
    const consumed = baseline.sentences.find(s => s.ordinal === ordinal);
    assert(!byId.has(consumed.id), `Consumed record is still active: ${ordinal}`);
  }
  for (const n of d.ordinals) touched.add(baseline.sentences.find(s=>s.ordinal===n).id);
}
let unchanged = 0;
for (const s of baseline.sentences) if (!touched.has(s.id)) {
  assert.deepEqual(byId.get(s.id)?.document,s.document,`Unrelated edit ${s.ordinal}`);
  unchanged++;
}
const brackets = initial.bracketIssues.map(issue => {
  const correction = decisions.find(d=>d.ordinals.includes(issue.ordinal));
  assert(correction || notes[issue.ordinal],`Missing bracket review ${issue.ordinal}`);
  return {...issue,visualReviewed:true,status:correction?'saved':'unresolved',
    evidence:correction?.evidence || notes[issue.ordinal],runId:correction?.runId};
});
const currentScan = scanBoundaries(current);
const blockers = read('docs/editorial-review/boundary-source-blockers.json').decisions;
const frozen = read('docs/editorial-review/boundary-pending-r905.json');
const auditEvidence = {corrections: [...decisions, ...followups], blockers, unchanged: verifiedUnchanged.decisions};
const auditedWindows = frozen.windows.map(w => auditWindow(w, auditEvidence));
const dispositions = {};
for (const w of auditedWindows) dispositions[w.status] = (dispositions[w.status] || 0) + 1;
const audit = {
  baselineRevision: frozen.revision, currentRevision: current.work.revision,
  scope: 'Frozen r905 priority contexts only. Source-unreadable and partial repairs are NOT resolved errors. No whole-book spelling certification.',
  priorityAuditComplete: !dispositions['requires-review'], textFullyCorrected: false,
  totalWindows: auditedWindows.length, dispositions, windows: auditedWindows,
};
writeFileSync('docs/editorial-review/boundary-priority-audit.json', JSON.stringify(audit, null, 2) + '\n');
writeFileSync(`${root}/boundaries-current.json`, JSON.stringify(currentScan,null,2)+'\n');
const verifiedOrdinals = new Set(verifiedUnchanged.decisions.flatMap(d => d.ordinals));
const priorityReasons = new Set(['lowercase-continuation', 'unmatched-closing-bracket',
  'unmatched-opening-bracket', 'possible-hyphenation', 'suspicious-punctuation-sequence']);
const pendingWindows = currentScan.boundaries
  .filter(b => b.reasons.some(reason => priorityReasons.has(reason)))
  .map(b => ({
    ordinals: b.ordinals,
    pages: [...new Set(b.sourceRefs.map(ref => ref.page))],
    reasons: b.reasons.filter(reason => priorityReasons.has(reason)),
    text: b.window.map(row => ({ordinal: row.ordinal, text: row.text})),
    punctuationPreviouslyReviewed: b.window.some(row => verifiedOrdinals.has(row.ordinal)),
    sourceNotes: b.window.filter(row => notes[row.ordinal])
      .map(row => ({ordinal: row.ordinal, evidence: notes[row.ordinal]})),
    status: 'requires-review',
  })).map(w => auditWindow(w, {...auditEvidence, corrections: []}));
writeFileSync('docs/editorial-review/boundary-pending.json', JSON.stringify({
  revision: current.work.revision,
  complete: false,
  scope: 'Priority scanner windows, not proven errors or a complete inventory of spelling errors. Previously reviewed punctuation does not certify the entire window.',
  windows: pendingWindows,
}, null, 2)+'\n');
const report = {workId:current.work.id,baselineRevision:475,currentRevision:current.work.revision,
  complete:false,published:false,publishedReaderRevision:475,
  initialBoundaries:initial.summary.boundaries,initialFlaggedWindows:initial.summary.flagged,
  bracketCharactersReviewed:brackets.length,bracketCharactersCorrected:brackets.filter(b=>b.status==='saved').length,
  bracketCharactersUnresolved:brackets.filter(b=>b.status==='unresolved').length,
  savedCorrections:decisions.length + followups.length,followupCorrections:followups.length,
  mergedRecords:baseline.sentences.length-current.sentences.length,
  unchangedRecordsVerified:unchanged,
  visuallyVerifiedUnchangedWindows:verifiedUnchanged.decisions.reduce((n,d)=>n+d.ordinals.length,0),
  verifiedUnchanged:verifiedUnchanged.decisions,
  currentStructuralScan:currentScan.summary,
  currentLowercaseContinuationWindows:currentScan.boundaries.filter(b=>b.reasons.includes('lowercase-continuation')).length,
  priorityWindowsForReview:pendingWindows.length,
  priorityAudit: {...audit, windows: undefined},
  currentUnreviewedPriorityWindows: pendingWindows.filter(w => w.status === 'requires-review').length,
  remaining:[audit.priorityAuditComplete ? 'Unreadable source fragments remain explicitly unresolved' : 'Frozen priority audit is unfinished',
    'Full spelling audit outside the flagged contexts is not certified',
    'Publication after review completion'],brackets,decisions,followups};
writeFileSync('docs/editorial-review/boundary-progress.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({...report,brackets:undefined,decisions:undefined,followups:undefined,verifiedUnchanged:undefined}));
