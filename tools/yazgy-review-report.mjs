import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
const dir='.codex/editorial/yazgy';
const snapshot=JSON.parse(readFileSync(`${dir}/snapshot.json`,'utf8'));
const mapping=JSON.parse(readFileSync(`${dir}/mapping.json`,'utf8'));
const attached=JSON.parse(readFileSync(`${dir}/attached-sources.json`,'utf8'));
const applied=JSON.parse(readFileSync(`${dir}/opening-results.json`,'utf8'));
const sourceById=new Map(attached.pages.map(p=>[p.sourceId,p.archiveImage]));
const sourceBySentence=new Map(mapping.records.map(r=>[r.sentenceId,r]));
const pages=attached.pages.map(p=>({page:p.archiveImage,sourceId:p.sourceId,file:p.file,url:p.url,sha256:p.sha256,
  sentenceIds:[],unknownForms:[],boundaryUnits:[],visuallyProofread:false}));
for(const row of snapshot.sentences) {
  const expected=sourceBySentence.get(row.id);
  if(!expected)throw new Error(`Missing mapping ${row.id}`);
  const actual=row.document.sourceRefs.map(r=>{if(r.page!==1)throw new Error('Image page must be 1');return sourceById.get(r.sourceId)}).sort((a,b)=>a-b);
  if(JSON.stringify(actual)!==JSON.stringify(expected.pages))throw new Error(`Source mismatch ${row.id}`);
  if(createHash('sha256').update(row.document.text).digest('hex')!==expected.textSha256)throw new Error(`Text changed during mapping ${row.id}`);
  if(createHash('sha256').update(JSON.stringify(row.document.tokens)).digest('hex')!==expected.tokensSha256)throw new Error(`Tokens changed during mapping ${row.id}`);
  for(const page of pages.filter(p=>actual.includes(p.page))) {
    page.sentenceIds.push(row.id);
    if(actual.length>1)page.boundaryUnits.push(row.id);
    for(const t of row.document.tokens)if(/\p{L}/u.test(t.surface)&&(!t.analysis||['Unknown','Error'].includes(t.analysis)||t.analysis.includes('+?'))) {
      page.unknownForms.push({sentenceId:row.id,surface:t.surface});
    }
  }
}
mkdirSync('materials/tt/yazgy',{recursive:true});
const report={workId:snapshot.work.id,revision:snapshot.work.revision,sourceIssue:'1948-10',
  rejectedLocalSource:{project:'D:/Java/kazan-utlary-ocr-java',directory:'out/issue',issue:'1948-8',reason:'Different installment; no forced mapping'},
  sourcePages:pages.length,mappedRecords:snapshot.sentences.length,mappingMethod:mapping.method,mappingNotes:mapping.notes,
  pageBoundaryUnits:mapping.records.filter(r=>r.boundaryReview).length,sourceRefsVerified:true,
  textChangesDuringMapping:0,published:false,proofreadingComplete:false,
  completedCorrections:applied,deferred:'Remaining text, punctuation, footnotes, split words and exact reader page breaks require visual review',pages};
writeFileSync('docs/editorial-review/yazgy-progress.json',JSON.stringify(report,null,2)+'\n');
writeFileSync('materials/tt/yazgy/source-mapping.json',JSON.stringify({workId:snapshot.work.id,revision:snapshot.work.revision,
  sources:attached.pages,records:mapping.records},null,2)+'\n');
writeFileSync(`materials/tt/yazgy/yazgy-draft-r${snapshot.work.revision}.jsonl`,snapshot.sentences.map(r=>JSON.stringify(r)).join('\n')+'\n');
writeFileSync(`materials/tt/yazgy/yazgy-draft-r${snapshot.work.revision}.txt`,snapshot.sentences.map(r=>r.document.text).join(''));
console.log(JSON.stringify({...report,pages:undefined,completedCorrections:applied.length}));
