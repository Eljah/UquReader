import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {connectEditor} from './editorial-client.mjs';
import {isDeepStrictEqual} from 'node:util';
const dir='.codex/editorial/yazgy';
const planPath=process.argv[2]||'docs/editorial-review/yazgy-page37-pass2.json';
const plan=JSON.parse(readFileSync(planPath,'utf8'));
const baseline=readFileSync(`materials/tt/yazgy/yazgy-draft-r${plan.baselineRevision||813}.jsonl`,'utf8').trim().split('\n').map(JSON.parse);
const sources=JSON.parse(readFileSync(`${dir}/attached-sources.json`,'utf8')).pages;
const output=planPath.replace(/\.json$/,'-results.json');
const results=existsSync(output)?JSON.parse(readFileSync(output,'utf8')):[];
const c=await connectEditor();
try {
  const available=await c.call('list_analyzers',{language:'tt'});
  if(!available.analyzers.some(a=>a.id==='uqureader-tatar'&&a.available))throw new Error('Tatar analyzer unavailable');
  for(const edit of plan.changes){
    const original=baseline.find(r=>r.ordinal===edit.ordinal);
    if(!original)throw new Error('Missing baseline unit');
    if(results.some(r=>r.sentenceId===original.id))continue;
    const current=await c.call('get_sentence',{sentence_id:original.id});
    if(current.work_id!==plan.workId)throw new Error('Wrong work');
    if(current.revision!==original.revision){
      const history=await c.call('sentence_history',{sentence_id:current.id});
      const entry=history.revisions.find(r=>r.revision===current.revision);
      if(current.revision!==original.revision+1||current.document.text!==edit.sentences.join('')||!entry?.reason.startsWith(edit.reason+'; run='))throw new Error(`Stale baseline ${original.id}`);
      const validation=await c.call('validate_sentence',{sentence_id:current.id,expected_revision:current.revision,document:current.document,reason:'Verify saved revision after interrupted local checkpoint'});
      if(!validation.valid)throw new Error(JSON.stringify(validation));
      results.push({sentenceId:current.id,ordinal:edit.ordinal,before:original.document.text,after:current.document.text,runId:entry.reason.split('; run=').at(-1),sourceRefs:current.document.sourceRefs,reason:edit.reason,saved:true,revision:current.revision,recoveredCheckpoint:true});
      writeFileSync(output,JSON.stringify(results,null,2)+'\n');
      continue;
    }
    const refs=edit.pages.map(p=>({sourceId:sources.find(s=>s.archiveImage===p).sourceId,page:1}));
    const located=await c.call('find_sentence_sources',{work_id:plan.workId,text:current.document.text});
    if(!located.matches.some(r=>r.id===current.id))throw new Error('Source lookup mismatch');
    for(const ref of refs) await c.call('render_source_page',{source_id:ref.sourceId,page:1,scale:1});
    if(edit.sentences.join('')===current.document.text){
      results.push({sentenceId:current.id,ordinal:edit.ordinal,reviewed:true,saved:false,reason:edit.reason});
    }else{
      const runs=[];
      for(const text of edit.sentences)runs.push(await c.call('analyze_sentence',{language:'tt',analyzer:'uqureader-tatar',text}));
      let level=runs;
      while(level.length>1){
        const next=[];
        for(let i=0;i<level.length;i+=16){
          const chunk=level.slice(i,i+16);
          next.push(chunk.length===1?chunk[0]:await c.call('combine_analysis_runs',{language:'tt',run_ids:chunk.map(r=>r.runId)}));
        }
        level=next;
      }
      const run=level[0];
      const prepared=await c.call('prepare_sentence_revision',{sentence_id:current.id,run_id:run.runId});
      if(prepared.expectedRevision!==current.revision)throw new Error('Concurrent edit');
      const doc=prepared.document;
      if(doc.text!==edit.sentences.join(''))throw new Error('Analyzer changed transcription');
      doc.sourceRefs=refs;
      // Adjacent-unit repairs can move words across a stable record boundary.
      const donors=(edit.translationSourceOrdinals||[]).flatMap(ordinal=>{
        const row=baseline.find(r=>r.ordinal===ordinal);
        if(!row)throw new Error('Missing translation donor');
        return row.document.tokens;
      });
      for(const token of doc.tokens){
        const translations=donors.filter(t=>t.surface===token.surface&&t.analysis===token.analysis).flatMap(t=>t.translations||[]);
        if(translations.length)token.translations=[...new Set([...(token.translations||[]),...translations])];
      }
      const footnoteOffset=edit.footnoteStart===undefined?Infinity:[...edit.sentences.slice(0,edit.footnoteStart).join('')].length;
      for(const token of doc.tokens){
        if(edit.pages.length===1)token.sourcePage=edit.pages[0];
        if(token.start>=footnoteOffset){token.role='footnote';token.footnoteId='yazgy-1948-10-continuation';}
        if(edit.headingSurfaces?.includes(token.surface))token.role='heading';
      }
      const args={sentence_id:current.id,expected_revision:current.revision,document:doc,run_id:run.runId,reason:edit.reason};
      const validation=await c.call('validate_sentence',args);
      if(!validation.valid)throw new Error(JSON.stringify(validation));
      const saved=await c.call('save_sentence',args);
      if(!saved.saved)throw new Error(JSON.stringify(saved));
      const actual=await c.call('get_sentence',{sentence_id:current.id});
      if(!isDeepStrictEqual(actual.document,doc))throw new Error('Saved document mismatch');
      results.push({sentenceId:current.id,ordinal:edit.ordinal,before:current.document.text,after:doc.text,runId:run.runId,sourceRefs:refs,reason:edit.reason,...saved});
    }
    writeFileSync(output,JSON.stringify(results,null,2)+'\n');
    console.log(`Reviewed ordinal ${edit.ordinal}`);
  }
} finally {c.close();}
