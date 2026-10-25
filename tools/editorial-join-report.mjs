import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';
const root='.codex/editorial/review';
const read=path=>JSON.parse(readFileSync(path,'utf8'));
const baseline=read(`${root}/snapshot-r64.json`);
const current=read(`${root}/snapshot.json`);
const plans=read(`${root}/joins/plan.json`);
const ledger=read('docs/editorial-review/join-candidates.json');
const byId=new Map(current.sentences.map(s=>[s.id,s]));
const results=new Map(readdirSync(`${root}/joins/results`).filter(f=>f.endsWith('.json'))
  .map(f=>read(`${root}/joins/results/${f}`)).map(r=>[r.sentenceId,r]));
const prior=baseline.sentences.filter(s=>s.revision>1);
for(const s of prior) assert.deepEqual(byId.get(s.id)?.document,s.document,`Previous correction changed: ${s.ordinal}`);
const applied=[];
for(const plan of plans){
  const result=results.get(plan.sentenceId),actual=byId.get(plan.sentenceId);
  assert(result?.saved,`Missing successful checkpoint: ${plan.ordinal}`);
  assert.equal(actual.document.text,plan.after,`Server text mismatch: ${plan.ordinal}`);
  assert.equal(actual.revision,result.newRevision);
  assert.deepEqual(actual.document.sourceRefs,plan.sourceRefs);
  for(const e of plan.edits){
    const ts=actual.document.tokens.filter(t=>t.start>=e.newStart && t.end<=e.newEnd);
    assert(ts.length>0,`No replacement tokens: ${plan.ordinal}`);
    for(const t of ts) for(const [key,value] of Object.entries(e.layout)) assert.equal(t[key],value);
  }
  applied.push({sentenceId:plan.sentenceId,ordinal:plan.ordinal,candidateIds:plan.candidateIds,
    runId:result.runId,revision:actual.revision,sourceRefs:actual.document.sourceRefs,
    edits:plan.edits.map(e=>({ids:e.ids,before:e.before,after:e.replacement})),
    unknownJoined:result.joinedAnalyses.filter(e=>e.tokens.some(t=>t.analysis==='Unknown')).map(e=>({ids:e.ids,text:e.replacement}))});
}
for(const entry of ledger){
  if(entry.status==='accepted' || entry.status==='saved'){
    const result=results.get(entry.sentenceId);
    assert(result?.saved);
    entry.status='saved'; entry.runId=result.runId; entry.revision=result.newRevision;
  }
}
assert.equal(ledger.length,522);
assert.equal(new Set(ledger.map(e=>e.id)).size,522);
assert(ledger.every(e=>e.visualReviewed && ['saved','rejected','unresolved'].includes(e.status)));
const publication=existsSync('docs/editorial-review/publication.json') ? read('docs/editorial-review/publication.json') : null;
const report={workId:current.work.id,baselineRevision:64,currentRevision:current.work.revision,
  candidateReviewComplete:true,allApprovedCorrectionsSaved:true,fullBookProofreadingComplete:false,
  published:publication?.workId===current.work.id && publication?.revision===current.work.revision,publication,
  candidateCount:522,savedCandidates:ledger.filter(e=>e.status==='saved').length,
  rejectedCandidates:ledger.filter(e=>e.status==='rejected').length,
  unresolvedCandidates:ledger.filter(e=>e.status==='unresolved').length,
  correctedSentences:applied.length,wordEdits:applied.reduce((n,s)=>n+s.edits.length,0),
  previousCorrectedSentencesPreserved:prior.length,totalSentences:current.sentences.length,
  candidateSourcePages:[...new Set(ledger.flatMap(e=>e.sourceRefs.map(s=>s.page)))].sort((a,b)=>a-b),
  unknownJoinedWords:applied.flatMap(s=>s.unknownJoined.map(e=>({...e,sentenceId:s.sentenceId,ordinal:s.ordinal}))),
  unresolved:ledger.filter(e=>e.status==='unresolved'),applied};
const control=read(`${root}/hfst-final-scan.json`);
assert.equal(control.summary.workRevision,current.work.revision);
const extra=read('docs/editorial-review/control-scan-decisions.json');
const remaining=[];
for(const s of control.sentences) for(const c of s.joinCandidates){
  const decision=ledger.find(e=>e.sentenceId===s.sentenceId && e.proposed===c.joined)
    ?? extra.find(e=>e.ordinal===s.ordinal && e.joined===c.joined);
  assert(decision && ['rejected','unresolved'].includes(decision.status),`Unreviewed control candidate ${s.ordinal}: ${c.joined}`);
  remaining.push({sentenceId:s.sentenceId,ordinal:s.ordinal,joined:c.joined,
    baselineCandidateId:decision.id,status:decision.status,reason:decision.reason});
}
report.controlScan={...control.summary,allRemainingCandidatesClassified:true,
  rejected:remaining.filter(e=>e.status==='rejected').length,
  unresolved:remaining.filter(e=>e.status==='unresolved').length,remaining};
writeFileSync('docs/editorial-review/join-candidates.json',JSON.stringify(ledger,null,2)+'\n');
writeFileSync('docs/editorial-review/join-progress.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({...report,applied:undefined,unresolved:undefined,
  controlScan:{...report.controlScan,remaining:undefined},unknownJoinedWords:report.unknownJoinedWords.length}));
