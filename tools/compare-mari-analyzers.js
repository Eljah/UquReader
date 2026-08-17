#!/usr/bin/env node

const fs = require('fs');
const https = require('https');
const path = require('path');

const root = path.resolve(__dirname, '..');
const jsonlPath = process.argv[2] || path.join(root, 'android-app', 'src', 'main', 'assets', 'elnet_puncheryshte.ttmorph.jsonl');
const outPath = process.argv[3] || path.join(root, '.codex', 'mari', 'vienna-comparison.json');

const explicit = ['спортсменла', 'тазбор', 'пӱнчерыште', 'вагонышто', 'тудо', 'коеш', 'кечын'];
const words = pickWords(jsonlPath, 80, explicit);

postAnalyzer(words.join(' ')).then((html) => {
  const vienna = parseVienna(html);
  const local = loadLocal(jsonlPath, words);
  const rows = words.map((word) => ({
    word,
    local: local.get(word) || [],
    vienna: vienna.get(word) || [],
  }));
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(rows, null, 2), 'utf8');
  printSummary(rows);
}).catch((error) => {
  console.error(error);
  process.exit(1);
});

function pickWords(file, limit, first) {
  const counts = new Map();
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const row = JSON.parse(line);
    const word = String(row.surface || '').toLowerCase();
    if (!/^[а-яёӓӧӱҥӹ]+(?:[-'][а-яёӓӧӱҥӹ]+)*$/iu.test(word)) continue;
    counts.set(word, (counts.get(word) || 0) + 1);
  }
  const result = [];
  for (const word of first) {
    if (!result.includes(word)) result.push(word);
  }
  for (const [word] of [...counts.entries()].sort((a, b) => b[1] - a[1])) {
    if (result.length >= limit) break;
    if (!result.includes(word)) result.push(word);
  }
  return result;
}

function loadLocal(file, wanted) {
  const wantedSet = new Set(wanted);
  const result = new Map();
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const row = JSON.parse(line);
    const word = String(row.surface || '').toLowerCase();
    if (!wantedSet.has(word)) continue;
    if (!result.has(word)) result.set(word, []);
    const target = result.get(word);
    if (!target.some((entry) => entry.analysis === row.analysis)) {
      target.push({ analysis: row.analysis, translations: row.translations || [] });
    }
  }
  return result;
}

function postAnalyzer(input) {
  const body = new URLSearchParams({ inField: input, dnt: 'dnt' }).toString();
  return new Promise((resolve, reject) => {
    const request = https.request({
      hostname: 'mari-language.univie.ac.at',
      path: '/analyzer.php?int=0',
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
      },
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    });
    request.on('error', reject);
    request.end(body);
  });
}

function parseVienna(html) {
  const result = new Map();
  const cells = html.split('<!--NEXTWORD-->');
  for (const cell of cells) {
    const variants = [...cell.matchAll(/<table>([\s\S]*?)<\/table>/g)].map((match) => match[1]);
    for (const variant of variants) {
      const word = stripTags(readRow(variant, 'headword')).replace(/<!--.*?-->/g, '').trim().toLowerCase();
      if (!word) continue;
      const entry = {
        div: readCells(variant, 'div'),
        gloss: readCells(variant, 'gloss').map(stripTags).map((value) => value.trim()),
        pos: readCells(variant, 'pos').map(stripTags).map((value) => value.trim()),
      };
      if (!result.has(word)) result.set(word, []);
      result.get(word).push(entry);
    }
  }
  return result;
}

function readRow(fragment, cls) {
  const match = fragment.match(new RegExp(`<tr class="${cls}">([\\s\\S]*?)<\\/tr>`));
  return match ? match[1] : '';
}

function readCells(fragment, cls) {
  return [...readRow(fragment, cls).matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map((match) => match[1]);
}

function stripTags(value) {
  return String(value || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
}

function printSummary(rows) {
  let localOnly = 0;
  let viennaOnly = 0;
  let ambiguousVienna = 0;
  for (const row of rows) {
    if (row.local.length && !row.vienna.length) localOnly++;
    if (!row.local.length && row.vienna.length) viennaOnly++;
    if (row.vienna.length > 1) ambiguousVienna++;
  }
  console.log(`Compared ${rows.length} words`);
  console.log(`Vienna ambiguous: ${ambiguousVienna}`);
  console.log(`Local only: ${localOnly}`);
  console.log(`Vienna only: ${viennaOnly}`);
  for (const row of rows.slice(0, 12)) {
    console.log(`\n${row.word}`);
    console.log(`  local: ${row.local.map((entry) => entry.analysis).join(' | ') || '-'}`);
    console.log(`  vienna: ${row.vienna.map((entry) => `${entry.div.join('-')} [${entry.pos.join(' ')}] ${entry.gloss.join(' ')}`).join(' | ') || '-'}`);
  }
}
