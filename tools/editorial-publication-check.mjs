import assert from 'node:assert/strict';
import {readFileSync, writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const read = p => JSON.parse(readFileSync(p, 'utf8'));
const publication = read('docs/editorial-review/publication.json');
const report = read('docs/editorial-review/gutter-restoration-results.json');
assert.equal(publication.revision, report.currentRevision);
const file = `materials/mhr/elnet/${publication.id}.ttmorph.jsonl`;
const bytes = readFileSync(file);
assert.equal(createHash('sha256').update(bytes).digest('hex'), publication.sha256);
const tokens = bytes.toString('utf8').trimEnd().split('\n').map(JSON.parse);
assert.equal(tokens.map(t => (t.prefix || '') + t.surface).join(''), readFileSync(report.textFile, 'utf8'));
const touched = new Set(report.changes.map(c => c.sentenceId));
const pages = [...new Set(tokens.filter(t => touched.has(t.sentenceId)).map(t => t.pageIndex))].sort((a,b) => a-b);
const base = process.env.UQUREADER_VERIFY_URL || 'https://uqureader.tatar';
async function get(path) {
  const r = await fetch(base + path, {signal: AbortSignal.timeout(30000)});
  assert.equal(r.status, 200, `HTTP ${r.status}: ${path}`);
  return r.json();
}
const work = (await get('/api/works')).works.find(w => w.id === publication.id);
assert(work, 'Published edition absent from public catalog');
assert.equal(work.tokenCount, tokens.length);
assert.equal(work.language, 'mhr');
const verifiedGroups = new Set();
const timings = [];
for (const page of pages) {
  const start = performance.now();
  const response = await get(`/api/works/${publication.id}/tokens?page=${page}`);
  timings.push({pageIndex:page, ms:Math.round(performance.now()-start)});
  const expected = tokens.flatMap((t,index) => t.pageIndex === page ? [{...t,index}] : []);
  assert.equal(response.tokens.length, expected.length, `Page ${page} token count`);
  for (const [i, actual] of response.tokens.entries()) {
    for (const key of ['index','prefix','surface','analysis','pageIndex','sourcePage','role','footnoteId']) {
      assert.equal(actual[key], expected[i][key] ?? (['prefix','role','footnoteId'].includes(key) ? '' : undefined), `${page}:${i}:${key}`);
    }
    if (touched.has(expected[i].sentenceId)) verifiedGroups.add(expected[i].sentenceId);
  }
}
assert.equal(verifiedGroups.size, touched.size, 'Some restorations not visible through public API');
const verification = {publicationId:publication.id,revision:publication.revision,
  publicUrl:base,verifiedGroups:verifiedGroups.size,verifiedPages:pages.length,
  tokenCount:tokens.length,sha256:publication.sha256,textMatchesSnapshot:true,timings};
writeFileSync('docs/editorial-review/publication-verification.json', JSON.stringify(verification,null,2)+'\n');
report.published = true;
report.publication = publication;
writeFileSync('docs/editorial-review/gutter-restoration-results.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({...verification,timings:undefined}));
