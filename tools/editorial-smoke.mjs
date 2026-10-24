// Exercise the deployed MCP protocol over its restricted SSH transport.
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ssh = spawn('ssh', ['-T', '-i', resolve('.codex/uqureader-editor-key'), '-o', 'BatchMode=yes',
  '-o', 'ConnectTimeout=10', 'uqureader@192.168.0.120'], { stdio: ['pipe', 'pipe', 'inherit'] });
let sequence = 0;
let buffer = '';
const pending = new Map();
ssh.stdout.setEncoding('utf8');
ssh.stdout.on('data', chunk => {
  buffer += chunk;
  while (buffer.includes('\n')) {
    const end = buffer.indexOf('\n');
    const line = buffer.slice(0, end);
    buffer = buffer.slice(end + 1);
    if (!line.trim()) continue;
    const response = JSON.parse(line);
    const p = pending.get(response.id);
    if (p) {
      pending.delete(response.id);
      clearTimeout(p.timer);
      response.error ? p.reject(new Error(JSON.stringify(response.error))) : p.resolve(response.result);
    }
  }
});
ssh.on('exit', code => {
  for (const p of pending.values()) {
    clearTimeout(p.timer);
    p.reject(new Error(`SSH exited: ${code}`));
  }
  pending.clear();
});
function request(method, params) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Timeout: ${method}`)); }, 180000);
    pending.set(id, { resolve, reject, timer });
    ssh.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
}
async function call(name, args = {}) {
  const result = await request('tools/call', { name, arguments: args });
  if (result.isError) throw new Error(JSON.stringify(result));
  return result;
}
function data(result) {
  if (result.structuredContent) return result.structuredContent.result ?? result.structuredContent;
  return JSON.parse(result.content.find(c => c.type === 'text').text);
}
try {
  console.log('initialize', (await request('initialize', { protocolVersion: '2024-11-05', capabilities: {},
    clientInfo: { name: 'uqureader-smoke', version: '1' } })).serverInfo);
  ssh.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  console.log('tools', (await request('tools/list', {})).tools.map(t => t.name));
  const works = data(await call('list_works')).works;
  console.log('works', works.map(w => ({ id: w.id, title: w.title, language: w.language })));
  const mari = works.find(w => w.language === 'mhr');
  const sentences = data(await call('list_sentences', { work_id: mari.id, limit: 2 })).sentences;
  console.log('sentences', sentences.map(s => ({ id: s.id, text: s.document.text.slice(0, 150), sources: s.document.sourceRefs })));
  const found = data(await call('find_sentence_sources', { work_id: mari.id, text: sentences[0].document.text }));
  if (!found.matches.length) throw new Error('Sentence text lookup failed');
  const ref = sentences[0].document.sourceRefs[0];
  const rendered = await call('render_source_page', { source_id: ref.sourceId, page: ref.page });
  const img = rendered.content.find(c => c.type === 'image');
  if (!img || img.mimeType !== 'image/png') throw new Error('Missing page image');
  mkdirSync('.codex/editorial', { recursive: true });
  writeFileSync('.codex/editorial/source-page.png', Buffer.from(img.data, 'base64'));
  console.log('rendered image bytes', Buffer.from(img.data, 'base64').length);
  console.log('analyzers', data(await call('list_analyzers', { language: 'mhr' })));
  const analysis = data(await call('analyze_sentence', { language: 'mhr', analyzer: 'vienna', text: '\u0428\u043e\u0448\u043e.' }));
  console.log('Vienna', { recognized: analysis.recognizedCount, runId: analysis.runId });
  const validation = data(await call('validate_sentence', { sentence_id: sentences[0].id,
    expected_revision: sentences[0].revision, document: sentences[0].document, reason: 'Smoke test: unchanged source' }));
  if (!validation.valid || validation.saved) throw new Error('Validation contract failed');
  console.log('PASS: real SSH MCP, source lookup, PNG, analysis and dry-run');
} finally {
  ssh.stdin.end();
}
