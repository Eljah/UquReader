import {readFileSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const directory='.codex/editorial/yazgy';
const snapshot=JSON.parse(readFileSync(`${directory}/snapshot.json`,'utf8'));
const review=JSON.parse(readFileSync('docs/editorial-review/yazgy-boundary-review.json','utf8'));
if(snapshot.work.revision!==4||snapshot.sentences.length!==809)throw new Error('Reviewed boundaries require the revision-4 snapshot');
const rows=[...snapshot.sentences].sort((a,b)=>a.ordinal-b.ordinal);
const byOrdinal=new Map(rows.map(r=>[r.ordinal,r]));
if(byOrdinal.size!==rows.length)throw new Error('Duplicate ordinals');
const boundaries=review.boundaries.map(([page,ordinal],i)=>{
  if(page!==37+i||!byOrdinal.has(ordinal)||(i&&ordinal<=review.boundaries[i-1][1]))throw new Error('Invalid boundary');
  return {page,ordinal,sentenceId:byOrdinal.get(ordinal).id};
});
if(boundaries.length!==37)throw new Error('Missing page');
const records=rows.map(r=>{
  const start=boundaries.filter(b=>b.ordinal<=r.ordinal).at(-1);
  const boundaryReview=start.page>37&&start.ordinal===r.ordinal;
  return {sentenceId:r.id,ordinal:r.ordinal,textSha256:createHash('sha256').update(r.document.text).digest('hex'),
    tokensSha256:createHash('sha256').update(JSON.stringify(r.document.tokens)).digest('hex'),
    pages:boundaryReview?[start.page-1,start.page]:[start.page],boundaryReview,
    method:'visual-boundary-review',proofread:false};
});
writeFileSync(`${directory}/mapping.json`,JSON.stringify({workId:snapshot.work.id,sourceRevision:4,
  method:'visual-boundary-review',exactTokenBoundaries:false,boundaries,notes:review.notes,records},null,2)+'\n');
console.log(JSON.stringify({records:records.length,pages:boundaries.length,boundaryUnits:records.filter(r=>r.boundaryReview).length}));
