// Resumable review driver. Automatic inspection is never labeled visual review.
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import {correctionIsApplied, applyLayoutOverrides, analysisSentences} from './editorial-boundary-state.mjs';
const directory = '.codex/editorial/review';
mkdirSync(directory, { recursive: true });
const child = spawn('ssh', ['-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
  '-i', resolve('.codex/uqureader-editor-key'), 'uqureader@192.168.0.120']);
child.stderr.on('data', data => process.stderr.write(data));
let buffer = '', counter = 0;
const pending = new Map();
child.stdout.setEncoding('utf8');
child.stdout.on('data', chunk => {
  buffer += chunk;
  while (buffer.includes('\n')) {
    const end = buffer.indexOf('\n');
    const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
    if (!line.trim()) continue;
    const result = JSON.parse(line), p = pending.get(result.id);
    if (p) {
      pending.delete(result.id); clearTimeout(p.timer);
      result.error ? p.reject(new Error(JSON.stringify(result.error))) : p.resolve(result.result);
    }
  }
});
child.on('exit', code => {
  for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new Error(`SSH exit ${code}`)); }
  pending.clear();
});
function rpc(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++counter;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('MCP timeout')); child.kill(); }, 180000);
    pending.set(id, { resolve, reject, timer });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
}
async function call(name, args) {
  const result = await rpc('tools/call', { name, arguments: args });
  if (result.isError) throw new Error(JSON.stringify(result));
  return result;
}
function data(result) { return result.structuredContent ?? JSON.parse(result.content.find(c => c.type === 'text').text); }
try {
  await rpc('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'uqureader-review', version: '1' } });
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  const mode = process.argv[2];
  if (mode === 'snapshot') {
    const works = data(await call('list_works', {})).works;
    const work = works.find(w => w.language === 'mhr');
    const sentences = [];
    for (let offset = 0; ; offset += 100) {
      const batch = data(await call('list_sentences', { work_id: work.id, offset, limit: 100 })).sentences;
      sentences.push(...batch);
      if (batch.length < 100) break;
    }
    writeFileSync(`${directory}/snapshot.json`, JSON.stringify({ work, sentences }, null, 2));
    if (!existsSync(`${directory}/baseline-snapshot.json`)) writeFileSync(`${directory}/baseline-snapshot.json`, JSON.stringify({ work, sentences }, null, 2));
    const unknown = t => ['Unknown', 'Error', ''].includes(t.analysis) || /\+\*\*\*\||\+\?/.test(t.analysis);
    const report = sentences.map(s => ({ id: s.id, ordinal: s.ordinal, revision: s.revision,
      text: s.document.text, sources: s.document.sourceRefs,
      unknown: s.document.tokens.filter(t => /\p{L}/u.test(t.surface) && unknown(t)).map(t => t.surface),
      pageBoundary: new Set(s.document.tokens.map(t => t.pageIndex)).size > 1,
      splitCandidates: s.document.tokens.flatMap((t, i, ts) => i && (t.prefix.includes('\n') || t.pageIndex !== ts[i-1].pageIndex || ts[i-1].surface === '-') && (unknown(t) || unknown(ts[i-1])) ? [i] : []) }));
    writeFileSync(`${directory}/inventory.json`, JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ workId: work.id, revision: work.revision, units: report.length,
      withUnknown: report.filter(s => s.unknown.length).length, unknownTokens: report.reduce((n,s) => n+s.unknown.length,0),
      pageBoundary: report.filter(s => s.pageBoundary).length, splitCandidates: report.filter(s => s.splitCandidates.length).length }));
  } else if (mode === 'report') {
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot.json`, 'utf8'));
    const decisions = readdirSync(`${directory}/decisions`).filter(f => f.endsWith('.json')).map(f => JSON.parse(readFileSync(`${directory}/decisions/${f}`, 'utf8'))).sort((a,b) => a.ordinal-b.ordinal);
    const analyses = readdirSync(`${directory}/analysis`).filter(f => f.endsWith('.json')).map(f => JSON.parse(readFileSync(`${directory}/analysis/${f}`, 'utf8')));
    const publication = existsSync('docs/editorial-review/publication.json') ? JSON.parse(readFileSync('docs/editorial-review/publication.json', 'utf8')) : null;
    const report = { workId: snapshot.work.id, totalUnits: snapshot.sentences.length,
      complete: false, published: publication?.workId === snapshot.work.id && publication?.revision === snapshot.work.revision, publication,
      structuralScanUnits: snapshot.sentences.length,
      baselineViennaRechecked: analyses.filter(a => a.analysis).length,
      baselineErrors: analyses.filter(a => a.error).length,
      correctedUnits: decisions.filter(d => d.saved).length,
      sourcePagesInspected: [...new Set(decisions.flatMap(d => d.sourcePages))].sort((a,b)=>a-b),
      oldUnknownInCorrectedUnits: decisions.filter(d => d.saved).reduce((n,d)=>n+d.oldUnknown,0),
      newUnknownInCorrectedUnits: decisions.filter(d => d.saved).reduce((n,d)=>n+d.newUnknown,0),
      remaining: ['Full sentence-level proofreading beyond the completed join-candidate review', 'Sentence boundary repairs and margin-only artifacts on inspected pages', 'Remaining unknown-word hypotheses'],
      decisions };
    if (existsSync(`${directory}/hfst-scan.json`)) report.wholeWorkHfstScan = JSON.parse(readFileSync(`${directory}/hfst-scan.json`, 'utf8')).summary;
    if (existsSync('docs/editorial-review/join-progress.json')) {
      const joins = JSON.parse(readFileSync('docs/editorial-review/join-progress.json', 'utf8'));
      report.joinCandidatePass = {candidateCount:joins.candidateCount,savedCandidates:joins.savedCandidates,
        rejectedCandidates:joins.rejectedCandidates,unresolvedCandidates:joins.unresolvedCandidates,
        correctedSentences:joins.correctedSentences,wordEdits:joins.wordEdits,
        candidateReviewComplete:joins.candidateReviewComplete,report:'join-progress.json'};
      report.totalCorrectedUnits = new Set([...decisions.filter(d=>d.saved).map(d=>d.sentenceId),
        ...joins.applied.map(d=>d.sentenceId)]).size;
      report.correctedUnitsScope = 'Initial pages 4-10 batch only; see totalCorrectedUnits and joinCandidatePass';
    }
    mkdirSync('docs/editorial-review', { recursive: true });
    writeFileSync('docs/editorial-review/mari-progress.json', JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ ...report, decisions: undefined }));
  } else if (mode === 'analyze-unknown') {
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot.json`, 'utf8'));
    const shard = Number(process.argv[3] || 0), shards = Number(process.argv[4] || 1);
    const limit = Number(process.argv[5] || Infinity);
    const bad = t => /\p{L}/u.test(t.surface) && (['Unknown', 'Error', ''].includes(t.analysis) || /\+\*\*\*\||\+\?/.test(t.analysis));
    const items = snapshot.sentences.filter(s => s.document.tokens.some(bad));
    mkdirSync(`${directory}/analysis`, { recursive: true });
    let completed = 0;
    for (const [index, s] of items.entries()) {
      if (index % shards !== shard) continue;
      const file = `${directory}/analysis/${s.id}-r${s.revision}.json`;
      if (existsSync(file)) continue;
      if (completed >= limit) break;
      const result = { sentenceId: s.id, revision: s.revision, ordinal: s.ordinal, text: s.document.text, oldUnknown: s.document.tokens.filter(bad).length, visualReviewed: false, saved: false };
      try { result.analysis = data(await call('analyze_sentence', { language: 'mhr', analyzer: 'vienna', text: s.document.text, refresh: true })); }
      catch (error) { result.error = String(error); }
      writeFileSync(file, JSON.stringify(result, null, 2));
      completed++;
      if (completed % 10 === 0) console.log(JSON.stringify({ shard, completed, ordinal: s.ordinal }));
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    console.log(JSON.stringify({ shard, completed, finished: true }));
  } else if (mode === 'probe-joins') {
    const { applyTokenEdits } = await import('./editorial-token-edits.mjs');
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot-r64.json`, 'utf8'));
    const candidates = JSON.parse(readFileSync(`${directory}/candidate-review/candidates.json`, 'utf8'));
    const hypotheses = JSON.parse(readFileSync('docs/editorial-review/join-hypotheses.json', 'utf8'));
    mkdirSync(`${directory}/joins/probes`, { recursive: true });
    for (const h of hypotheses) {
      const c = candidates.find(c=>c.id===h.ids[0]);
      const original = snapshot.sentences.find(s=>s.id===c.sentenceId);
      const {after,edits} = applyTokenEdits(original.document,[h]);
      const file = `${directory}/joins/probes/${h.ids[0]}.json`;
      if (existsSync(file)) continue;
      const result = {hypothesis:h,text:after,ordinal:original.ordinal,sentenceId:original.id};
      try {
        const a = data(await call('analyze_sentence',{language:'mhr',analyzer:'vienna',text:after}));
        result.runId=a.runId;
        result.tokens=a.tokens.filter(t=>t.start>=edits[0].newStart && t.end<=edits[0].newEnd);
      } catch(error) {result.error=String(error);}
      writeFileSync(file,JSON.stringify(result,null,2));
      console.log(JSON.stringify(result));
    }
  } else if (['apply-joins','apply-joins-tail','apply-joins-only'].includes(mode)) {
    const plans = JSON.parse(readFileSync(`${directory}/joins/plan.json`, 'utf8'));
    const only = mode === 'apply-joins-only' ? new Set(process.argv[3].split(',').map(Number)) : null;
    const shard = only ? 0 : Number(process.argv[3] || 0), shards = only ? 1 : Number(process.argv[4] || 1);
    mkdirSync(`${directory}/joins/results`, { recursive: true });
    for (const [index, plan] of plans.entries()) {
      if (index % shards !== shard) continue;
      if (only && !plan.candidateIds.some(id=>only.has(id))) continue;
      if (mode === 'apply-joins-tail' && index < Number(process.argv[5])) continue;
      const file = `${directory}/joins/results/${plan.sentenceId}.json`;
      const previous = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null;
      if (previous?.saved && previous.after === plan.after) continue;
      const decision = { ...plan, saved: false };
      try {
        const current = data(await call('get_sentence', { sentence_id: plan.sentenceId }));
        const expectedRevision = previous?.saved ? previous.newRevision : plan.revision;
        const expectedText = previous?.saved ? previous.after : plan.before;
        if (current.revision !== expectedRevision || current.document.text !== expectedText)
          throw new Error('Concurrent revision; refusing stale edits');
        if (previous?.saved) decision.followup = {revision:previous.newRevision,runId:previous.runId,text:previous.after};
        const analyzed = data(await call('analyze_sentence', { language: 'mhr', analyzer: 'vienna', text: plan.after }));
        decision.runId = analyzed.runId;
        decision.newUnknown = analyzed.unknownIndexes.length;
        decision.joinedAnalyses = plan.edits.map(e => ({ ids:e.ids, replacement:e.replacement,
          tokens:analyzed.tokens.filter(t=>t.start>=e.newStart && t.end<=e.newEnd) }));
        const prepared = data(await call('prepare_sentence_revision', { sentence_id: plan.sentenceId, run_id: analyzed.runId }));
        // A joined word belongs to its first source fragment, including across page boundaries.
        for (const e of plan.edits) for (const t of prepared.document.tokens)
          if (t.start >= e.newStart && t.end <= e.newEnd) Object.assign(t, e.layout);
        const args = { sentence_id: plan.sentenceId, expected_revision: current.revision, document: prepared.document,
          reason: `PDF visual join review; baseline r64 candidates ${plan.candidateIds.join(',')}`, run_id: analyzed.runId };
        const validation = data(await call('validate_sentence', args));
        if (!validation.valid) throw new Error(JSON.stringify(validation));
        const saved = data(await call('save_sentence', args));
        if (!saved.saved) throw new Error(JSON.stringify(saved));
        decision.saved = true; decision.newRevision = saved.revision;
      } catch (error) { decision.error = String(error); }
      if (!decision.saved && existsSync(file) && JSON.parse(readFileSync(file, 'utf8')).saved) continue;
      writeFileSync(file, JSON.stringify(decision, null, 2));
      console.log(JSON.stringify({ shard, ordinal:plan.ordinal, candidates:plan.candidateIds, saved:decision.saved, error:decision.error }));
    }
  } else if (mode === 'apply-reviewed') {
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot.json`, 'utf8'));
    const corrections = JSON.parse(readFileSync(process.argv[3], 'utf8'));
    mkdirSync(`${directory}/decisions`, { recursive: true });
    for (const correction of corrections) {
      const original = snapshot.sentences.find(s => s.ordinal === correction.ordinal);
      const file = `${directory}/decisions/${original.id}.json`;
      if (existsSync(file) && JSON.parse(readFileSync(file, 'utf8')).saved) continue;
      const current = data(await call('get_sentence', { sentence_id: original.id }));
      if (current.revision !== original.revision) throw new Error(`Revision changed for ${original.id}; refresh and review`);
      let text = original.document.text;
      for (const [before, after] of correction.replace) {
        if (text.split(before).length !== 2) throw new Error(`Ambiguous replacement: ${before}`);
        text = text.replace(before, after);
      }
      const decision = { sentenceId: original.id, ordinal: original.ordinal, before: original.document.text,
        after: text, sourcePages: correction.pages, evidence: correction.evidence, visualReviewed: true, saved: false };
      try {
        const baseline = data(await call('analyze_sentence', { language: 'mhr', analyzer: 'vienna', text: original.document.text }));
        const analyzed = data(await call('analyze_sentence', { language: 'mhr', analyzer: 'vienna', text }));
        decision.oldUnknown = baseline.unknownIndexes.length; decision.newUnknown = analyzed.unknownIndexes.length;
        decision.runId = analyzed.runId;
        const prepared = data(await call('prepare_sentence_revision', { sentence_id: original.id, run_id: analyzed.runId }));
        decision.layoutWarnings = prepared.warnings;
        const args = { sentence_id: original.id, expected_revision: current.revision, document: prepared.document,
          reason: `Visual review: ${correction.evidence}`, run_id: analyzed.runId };
        const validation = data(await call('validate_sentence', args));
        if (!validation.valid) throw new Error(JSON.stringify(validation));
        const saved = data(await call('save_sentence', args));
        if (!saved.saved) throw new Error(JSON.stringify(saved));
        decision.saved = true; decision.newRevision = saved.revision;
      } catch (error) { decision.error = String(error); }
      writeFileSync(file, JSON.stringify(decision, null, 2));
      console.log(JSON.stringify(decision));
    }
  } else if (mode === 'render') {
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot.json`, 'utf8'));
    const source = snapshot.sentences.find(s => s.document.sourceRefs.length).document.sourceRefs[0].sourceId;
    for (const page of process.argv.slice(3).map(Number)) {
      const result = await call('render_source_page', { source_id: source, page, scale: 2 });
      const img = result.content.find(c => c.type === 'image');
      writeFileSync(`${directory}/page-${page}.png`, Buffer.from(img.data, 'base64'));
      console.log(`page ${page}`);
    }
  } else if (mode === 'boundary-apply' || mode === 'boundary-followup' || mode === 'gutter-apply') {
    const followup = mode === 'boundary-followup';
    const gutter = mode === 'gutter-apply';
    const snapshot = JSON.parse(readFileSync(`${directory}/snapshot-r${gutter ? 941 : followup ? 905 : 475}.json`, 'utf8'));
    const corrections = gutter ? JSON.parse(readFileSync('docs/editorial-review/gutter-restorations.json', 'utf8')).corrections
      : JSON.parse(readFileSync(`docs/editorial-review/boundary-${followup ? 'followups' : 'corrections'}.json`, 'utf8'));
    const out = gutter ? `${directory}/gutter-results` : `${directory}/boundary-${followup ? 'followup-results' : 'results'}`;
    mkdirSync(out, {recursive:true});
    const shard = Number(process.argv[3] || 0), shards = Number(process.argv[4] || 1);
    if (!Number.isInteger(shard) || !Number.isInteger(shards) || shard < 0 || shard >= shards) throw new Error('Invalid shard');
    for (const [index, input] of corrections.entries()) {
      if (gutter && index % shards !== shard) continue;
      const correction = gutter ? {...input, evidence: `User-authorized conjectural restoration, NOT source-certified; PDF ${input.pages.join(',')}; ${input.note || 'Preferred hypotheses from the reviewed list'}; confidence=${input.confidence || 'contextual'}`} : input;
      const records = correction.ordinals.map(n => snapshot.sentences.find(s => s.ordinal === n));
      if (records.some(r => !r)) throw new Error('Missing baseline record');
      const file = `${out}/${records[0].id}.json`;
      const previous = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null;
      if (previous?.saved && (records.length === 1 || previous.merge?.saved)) continue;
      for (const ordinal of correction.requiresSaved || []) {
        const dependency = snapshot.sentences.find(s => s.ordinal === ordinal);
        const result = JSON.parse(readFileSync(`${out}/${dependency.id}.json`, 'utf8'));
        if (!result.saved) throw new Error(`Required correction not saved: ${ordinal}`);
        const actual = data(await call('get_sentence', {sentence_id: dependency.id}));
        if (actual.merged_into || actual.document.text !== result.after) throw new Error('Required correction changed');
      }
      const originalText = records.map(r => r.document.text).join('');
      let text = originalText;
      for (const [before, after] of correction.replacements) {
        if (text.split(before).length !== 2) throw new Error(`Ambiguous/missing replacement: ${before}`);
        text = text.replace(before, after);
      }
      const decision = {...correction, sentenceId:records[0].id, before:originalText, after:text, saved:false};
      try {
        let current = data(await call('get_sentence', {sentence_id:records[0].id}));
        const consumed = [];
        if (current.document.text === text) {
          for (const row of records.slice(1)) consumed.push(data(await call('get_sentence', {sentence_id:row.id})));
        }
        if (current.document.text === text && correctionIsApplied(current, text, consumed)) {
          decision.saved = true; decision.revision = current.revision;
        } else {
          // Analyze before any merge, so analyzer failure cannot change record structure.
          let analysis;
          if (gutter) {
            const parts = analysisSentences(text);
            const runs = [];
            for (const part of parts) {
              runs.push(data(await call('analyze_sentence', {language:'mhr', analyzer:'vienna', text:part})).runId);
            }
            analysis = data(await call('combine_analysis_runs', {language:'mhr', run_ids:runs}));
            decision.analysisParts = parts;
            decision.componentRunIds = runs;
            decision.unknownTokens = analysis.tokens.filter((_, i) => analysis.unknownIndexes.includes(i)).map(t => t.surface);
          } else {
            analysis = data(await call('analyze_sentence', {language:'mhr', analyzer:'vienna', text}));
          }
          decision.runId = analysis.runId;
          if (records.length > 1 && current.document.text !== originalText) {
            if (current.document.text !== records[0].document.text) throw new Error('Unexpected survivor text');
            const args = {sentence_ids:records.map(r=>r.id), expected_revisions:records.map(r=>r.revision), reason:correction.evidence};
            await call('merge_sentences', {...args, dry_run:true});
            decision.merge = data(await call('merge_sentences', args));
            current = data(await call('get_sentence', {sentence_id:records[0].id}));
          }
          if (current.document.text !== originalText) throw new Error('Baseline text changed');
          const prepared = data(await call('prepare_sentence_revision', {sentence_id:current.id, run_id:analysis.runId}));
          decision.warnings = prepared.warnings;
          applyLayoutOverrides(prepared.document, correction.layoutOverrides);
          const args = {sentence_id:current.id, expected_revision:current.revision, document:prepared.document,
            run_id:analysis.runId, reason:correction.evidence};
          const validation = data(await call('validate_sentence', args));
          if (!validation.valid) throw new Error(JSON.stringify(validation));
          const saved = data(await call('save_sentence', args));
          if (!saved.saved) throw new Error(JSON.stringify(saved));
          decision.saved = true; decision.revision = saved.revision;
        }
      } catch (error) { decision.error = String(error); }
      writeFileSync(file, JSON.stringify(decision, null, 2));
      console.log(JSON.stringify({ordinals:decision.ordinals,saved:decision.saved,error:decision.error}));
    }
  } else if (mode === 'gutter-translations') {
    const {translationIndex, restoreKnownTranslations} = await import('./editorial-translations.mjs');
    const baseline = JSON.parse(readFileSync(`${directory}/snapshot-r941.json`, 'utf8'));
    const index = translationIndex(baseline.sentences);
    const out = `${directory}/gutter-results`;
    for (const file of readdirSync(out).filter(f => f.endsWith('.json'))) {
      const path = `${out}/${file}`, decision = JSON.parse(readFileSync(path, 'utf8'));
      if (!decision.saved) throw new Error('Finish all text restorations first');
      if (decision.before === decision.after) continue;
      const current = data(await call('get_sentence', {sentence_id:decision.sentenceId}));
      if (current.merged_into || current.document.text !== decision.after) throw new Error('Restoration changed');
      const document = structuredClone(current.document);
      const added = restoreKnownTranslations(document, index);
      if (!added) continue;
      const args = {sentence_id:current.id, expected_revision:current.revision, document,
        reason:'Retain existing book dictionary translations for restored lemma variants; no text or morphology change'};
      const validation = data(await call('validate_sentence', args));
      if (!validation.valid) throw new Error(JSON.stringify(validation));
      const saved = data(await call('save_sentence', args));
      if (!saved.saved) throw new Error(JSON.stringify(saved));
      decision.dictionaryEntriesRestored = (decision.dictionaryEntriesRestored || 0) + added;
      decision.dictionaryRevision = saved.revision;
      writeFileSync(path, JSON.stringify(decision, null, 2));
      console.log(JSON.stringify({ordinals:decision.ordinals,dictionaryEntriesRestored:added}));
    }
  } else if (mode === 'call') {
    const request = JSON.parse(readFileSync(process.argv[3], 'utf8'));
    const result = data(await call(request.name, request.arguments));
    if (process.argv[4]) writeFileSync(process.argv[4], JSON.stringify(result, null, 2));
    else console.log(JSON.stringify(result));
  } else throw new Error('Use snapshot, render PAGE..., or call REQUEST.json [OUTPUT.json]');
} finally { child.stdin.end(); }
