import {readFileSync, writeFileSync, mkdirSync, existsSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {connectEditor} from './editorial-client.mjs';
const directory = '.codex/editorial/yazgy';
mkdirSync(directory,{recursive:true});
const client = await connectEditor();
try {
  const mode = process.argv[2];
  if (mode === 'snapshot') {
    const works = (await client.call('list_works')).works;
    const work = works.find(w=>w.metadata.readerAsset === 'yazgy_jillar.ttmorph.jsonl');
    if(!work) throw new Error('Yazgy editorial work not found');
    const sentences = [];
    for(let offset=0;;offset+=100) {
      const batch = (await client.call('list_sentences',{work_id:work.id,offset,limit:100})).sentences;
      sentences.push(...batch);
      if(batch.length<100)break;
    }
    writeFileSync(`${directory}/snapshot.json`,JSON.stringify({work,sentences},null,2));
    console.log(JSON.stringify({workId:work.id,revision:work.revision,sentences:sentences.length,start:sentences[0].document.text}));
  } else if (mode === 'upload') {
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot.json`,'utf8'));
    const manifest = JSON.parse(readFileSync(`${directory}/1948-10/sources.json`,'utf8'));
    const existing = (await client.call('find_sentence_sources',{work_id:snapshot.work.id,text:snapshot.sentences[0].document.text})).sources;
    const attached = [];
    for (const page of manifest.pages.filter(p=>p.archiveImage>=37 && p.archiveImage<=73)) {
      const bytes = readFileSync(`${directory}/1948-10/images/${page.file}`);
      if(createHash('sha256').update(bytes).digest('hex')!==page.sha256)throw new Error('Source hash changed');
      const previous = existing.find(s=>s.sha256===page.sha256);
      const source = previous ? {sourceId:previous.id} : await client.call('upload_source',{
        work_id:snapshot.work.id,name:`1948-10-${page.file}`,content_base64:bytes.toString('base64'),expected_sha256:page.sha256});
      attached.push({...page,...source,sourcePage:page.archiveImage,page:1});
      writeFileSync(`${directory}/attached-sources.json`,JSON.stringify({workId:snapshot.work.id,pages:attached},null,2));
      console.log(page.file,source.sourceId);
    }
    if(attached.length!==37)throw new Error('Expected all 37 source pages');
  } else if (mode === 'map') {
    const mapping = JSON.parse(readFileSync(`${directory}/mapping.json`,'utf8'));
    const attached = JSON.parse(readFileSync(`${directory}/attached-sources.json`,'utf8'));
    if(mapping.workId!==attached.workId)throw new Error('Work mismatch');
    const index = new Map(attached.pages.map(p=>[p.archiveImage,p.sourceId]));
    const results=[];
    for(const match of mapping.records) {
      const current=await client.call('get_sentence',{sentence_id:match.sentenceId});
      if(current.merged_into||createHash('sha256').update(current.document.text).digest('hex')!==match.textSha256) {
        throw new Error(`Stale mapping: ${match.sentenceId}`);
      }
      const sourceRefs=match.pages.map(page=>{
        if(!index.has(page))throw new Error(`Missing image ${page}`);
        return {sourceId:index.get(page),page:1};
      });
      let saved={saved:false,revision:current.revision};
      if(JSON.stringify(sourceRefs)!==JSON.stringify(current.document.sourceRefs)) {
        const document=structuredClone(current.document);
        document.sourceRefs=sourceRefs;
        const args={sentence_id:current.id,expected_revision:current.revision,document,
          reason:`Source mapping: Soviet adabiyaty 1948-10 images ${match.pages.join(',')}; ${mapping.method || 'OCR anchors'}; original text and analyses unchanged; ${match.boundaryReview?'both adjacent pages retained for boundary review':'single-page provenance'}; full proofreading pending`};
        const validation=await client.call('validate_sentence',args);
        if(!validation.valid)throw new Error(JSON.stringify(validation));
        saved=await client.call('save_sentence',args);
        if(!saved.saved)throw new Error(JSON.stringify(saved));
      }
      results.push({sentenceId:current.id,sourceRefs,...saved});
      writeFileSync(`${directory}/mapping-results.json`,JSON.stringify(results,null,2)+'\n');
      if(results.length%50===0)console.log(`Mapped ${results.length}/${mapping.records.length}`);
    }
    console.log(JSON.stringify({mapped:results.length,boundaryReview:mapping.records.filter(r=>r.boundaryReview).length}));
  } else if (mode === 'render') {
    const attached = JSON.parse(readFileSync(`${directory}/attached-sources.json`,'utf8'));
    const source = attached.pages.find(p=>p.archiveImage===Number(process.argv[3]));
    const image = await client.call('render_source_page',{source_id:source.sourceId,page:1,scale:Number(process.argv[4]||2)});
    writeFileSync(`${directory}/source-${source.archiveImage}.png`,Buffer.from(image.content.find(c=>c.type==='image').data,'base64'));
  } else if (mode === 'correct-opening') {
    const plan = JSON.parse(readFileSync('docs/editorial-review/yazgy-first-page-corrections.json','utf8'));
    const attached = JSON.parse(readFileSync(`${directory}/attached-sources.json`,'utf8'));
    const source = attached.pages.find(p=>p.archiveImage===37);
    const image = await client.call('render_source_page',{source_id:source.sourceId,page:1,scale:2});
    const block = image.content.find(c=>c.type==='image');
    writeFileSync(`${directory}/source-037.png`,Buffer.from(block.data,'base64'));
    const checkpoint = `${directory}/opening-results.json`;
    const results = existsSync(checkpoint) ? JSON.parse(readFileSync(checkpoint,'utf8')) : [];
    for (const change of plan.corrections.filter(c=>!c.deferred)) {
      const id = change.sentenceIds[0];
      if(results.some(r=>r.sentenceId===id&&r.saved))continue;
      let current = await client.call('get_sentence',{sentence_id:id});
      if(change.sentenceIds.length>1) {
        const rows=[];
        for(const sid of change.sentenceIds)rows.push(await client.call('get_sentence',{sentence_id:sid}));
        if(rows.slice(1).some(r=>!r.merged_into)) {
          if(rows.some(r=>r.merged_into))throw new Error('Partial merge requires inspection');
          await client.call('merge_sentences',{sentence_ids:rows.map(r=>r.id),expected_revisions:rows.map(r=>r.revision),reason:change.reason});
          current=await client.call('get_sentence',{sentence_id:id});
        }
      }
      const runs=[];
      for(const text of change.sentences)runs.push(await client.call('analyze_sentence',{language:'tt',analyzer:'uqureader-tatar',text}));
      const run=runs.length===1?runs[0]:await client.call('combine_analysis_runs',{language:'tt',run_ids:runs.map(r=>r.runId)});
      const prepared=await client.call('prepare_sentence_revision',{sentence_id:id,run_id:run.runId});
      prepared.document.sourceRefs=[{sourceId:source.sourceId,page:1}];
      for(const token of prepared.document.tokens){token.sourcePage=37;}
      const args={sentence_id:id,expected_revision:prepared.expectedRevision,document:prepared.document,run_id:run.runId,reason:change.reason};
      const validation=await client.call('validate_sentence',args);
      if(!validation.valid)throw new Error(JSON.stringify(validation));
      const saved=await client.call('save_sentence',args);
      if(!saved.saved)throw new Error(JSON.stringify(saved));
      results.push({sentenceId:id,before:current.document.text,after:prepared.document.text,runId:run.runId,
        sourceRef:prepared.document.sourceRefs[0],reason:change.reason,...saved});
      writeFileSync(checkpoint,JSON.stringify(results,null,2)+'\n');
      console.log(JSON.stringify({sentenceId:id,revision:saved.revision,saved:saved.saved}));
    }
  } else if (mode === 'call') {
    const request = JSON.parse(readFileSync(process.argv[3],'utf8'));
    const result = await client.call(request.name,request.arguments);
    if(process.argv[4])writeFileSync(process.argv[4],JSON.stringify(result,null,2));
    else console.log(JSON.stringify(result));
  } else throw new Error('Use snapshot or call REQUEST [OUTPUT]');
} finally {client.close();}
