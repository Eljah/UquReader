#!/usr/bin/env node

const fs = require('fs');
const https = require('https');
const path = require('path');

const root = path.resolve(__dirname, '..');
const inputJsonl = process.argv[2] || path.join(root, 'android-app', 'src', 'main', 'assets', 'elnet_puncheryshte.ttmorph.jsonl');
const outputJsonl = process.argv[3] || path.join(root, 'android-app', 'src', 'main', 'assets', 'elnet_puncheryshte.vienna.ttmorph.jsonl');
const cachePath = process.argv[4] || path.join(root, '.codex', 'mari', 'vienna-sentence-cache.json');

const REQUEST_DELAY_MS = Number(process.env.VIENNA_REQUEST_DELAY_MS || 350);
const REQUEST_TIMEOUT_MS = Number(process.env.VIENNA_REQUEST_TIMEOUT_MS || 90000);
const CONCURRENCY = Number(process.env.VIENNA_CONCURRENCY || 4);
const LOG_EVERY = Number(process.env.VIENNA_LOG_EVERY || 50);
const MAX_SENTENCES = Number(process.env.VIENNA_MAX_SENTENCES || 0);
const SKIP_ANALYSIS = process.env.VIENNA_SKIP_ANALYSIS === '1';
const MARI_WORD = /^\p{Script=Cyrillic}+(?:[-']\p{Script=Cyrillic}+)*$/u;
const SENTENCE_END = /^[.!?\u2026]+$/u;

process.on('uncaughtException', (error) => {
  console.error('Uncaught exception:', error && error.stack ? error.stack : error);
  process.exit(1);
});

process.on('unhandledRejection', (error) => {
  console.error('Unhandled rejection:', error && error.stack ? error.stack : error);
  process.exit(1);
});

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});

async function main() {
  const records = readJsonl(inputJsonl);
  const cache = loadCache(cachePath);
  const sentences = splitSentences(records);
  const remaining = sentences.filter((sentence) => sentence.words.length > 0 && !cache.has(sentence.text));
  const todo = MAX_SENTENCES > 0 ? remaining.slice(0, MAX_SENTENCES) : remaining;

  console.log(`Loaded ${records.length} records from ${inputJsonl}`);
  console.log(`Built ${sentences.length} sentence-like chunks`);
  console.log(`Vienna cache has ${cache.size} sentences; ${remaining.length} sentences need analysis`);
  if (MAX_SENTENCES > 0 && remaining.length > todo.length) {
    console.log(`This run is capped at ${todo.length} sentences`);
  }

  if (SKIP_ANALYSIS && todo.length > 0) {
    console.warn(`Skipping Vienna requests for ${todo.length} uncached sentences`);
  } else {
    await analyseSentences(todo, records, cache, cachePath);
  }

  const viennaRecords = convertRecords(records, sentences, cache);
  fs.mkdirSync(path.dirname(outputJsonl), { recursive: true });
  fs.writeFileSync(outputJsonl, viennaRecords.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8');

  const wordRecords = viennaRecords.filter((record) => MARI_WORD.test(record.surface));
  const analysed = wordRecords.filter((record) => record.analyses.length > 0);
  const ambiguous = wordRecords.filter((record) => record.analyses.length > 1);
  const unknown = wordRecords.length - analysed.length;
  console.log(`Wrote ${viennaRecords.length} records to ${outputJsonl}`);
  console.log(`Mari-like word tokens: ${wordRecords.length}`);
  console.log(`Analysed by Vienna: ${analysed.length}`);
  console.log(`Ambiguous by Vienna: ${ambiguous.length}`);
  console.log(`Unknown by Vienna: ${unknown}`);
}

function readJsonl(file) {
  return fs.readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.replace(/^\uFEFF/, ''))
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

function splitSentences(records) {
  const result = [];
  let start = 0;
  for (let i = 0; i < records.length; i++) {
    const surface = String(records[i].surface || '');
    if (SENTENCE_END.test(surface)) {
      pushSentence(records, result, start, i + 1);
      start = i + 1;
    } else if (String(records[i].prefix || '').includes('\n\n') && i > start) {
      pushSentence(records, result, start, i);
      start = i;
    }
  }
  if (start < records.length) pushSentence(records, result, start, records.length);
  return result;
}

function pushSentence(records, result, start, end) {
  const slice = records.slice(start, end);
  const text = slice.map((record) => `${record.prefix || ''}${record.surface || ''}`).join('').replace(/\s+/g, ' ').trim();
  const words = [];
  for (let i = start; i < end; i++) {
    if (MARI_WORD.test(String(records[i].surface || '').toLowerCase())) {
      words.push(i);
    }
  }
  if (text) result.push({ start, end, text, words });
}

function makeSentence(records, start, end) {
  const result = [];
  pushSentence(records, result, start, end);
  return result[0] || null;
}

function loadCache(file) {
  if (!fs.existsSync(file)) return new Map();
  const raw = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  return new Map(Object.entries(raw));
}

function saveCache(file, cache) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(Object.fromEntries(cache), null, 2) + '\n', 'utf8');
}

function convertRecords(records, sentences, cache) {
  const result = records.map((record) => ({
    ...record,
    analyses: [],
    analyzer: 'vienna',
  }));

  for (const sentence of sentences) {
    const sequence = cache.get(sentence.text) || [];
    let sequenceIndex = 0;
    for (const recordIndex of sentence.words) {
      const record = result[recordIndex];
      const word = String(record.surface || '').toLowerCase();
      while (sequenceIndex < sequence.length && sequence[sequenceIndex].word !== word) {
        sequenceIndex++;
      }
      const item = sequenceIndex < sequence.length ? sequence[sequenceIndex] : null;
      const analyses = item && item.word === word ? item.variants.map(toAnalysisRecord) : [];
      result[recordIndex] = {
        ...record,
        analysis: analyses[0]?.analysis || 'Unknown',
        analyses,
      };
      if (item && item.word === word) sequenceIndex++;
    }
  }

  for (const record of result) {
    if (!MARI_WORD.test(String(record.surface || '').toLowerCase())) {
      record.analysis = record.analysis || 'Sign';
    } else if (!record.analysis) {
      record.analysis = 'Unknown';
    }
  }

  return result;
}

function toAnalysisRecord(entry) {
  return {
    analysis: formatAnalysis(entry),
    lemma: entry.lemma,
    segments: entry.segments,
    gloss: entry.gloss,
    pos: entry.pos,
  };
}

function formatAnalysis(entry) {
  const lemma = entry.lemma || entry.segments[0] || 'Unknown';
  const pos = entry.pos.filter(Boolean).join('+');
  const gloss = entry.gloss.filter(Boolean).join('+');
  const segments = entry.segments.filter(Boolean).join('-');
  const tail = [pos, gloss].filter(Boolean).join('|');
  return tail ? `${lemma}+${tail} (${segments})` : `${lemma} (${segments})`;
}

async function analyseSentences(sentences, records, cache, cachePath) {
  let next = 0;
  let completed = 0;
  const workers = Array.from({ length: Math.max(1, CONCURRENCY) }, async (_, workerIndex) => {
    if (workerIndex > 0) await sleep(workerIndex * REQUEST_DELAY_MS);
    for (;;) {
      const index = next++;
      if (index >= sentences.length) return;
      await analyseSentenceWithRecords(sentences[index], records, cache);
      completed++;
      if (completed % 10 === 0 || completed === sentences.length) {
        saveCache(cachePath, cache);
      }
      if (completed % LOG_EVERY === 0 || completed === sentences.length) {
        console.log(`Analysed ${completed} / ${sentences.length}`);
      }
      await sleep(REQUEST_DELAY_MS);
    }
  });
  await Promise.all(workers);
  saveCache(cachePath, cache);
}

async function analyseSentenceWithRecords(sentence, records, cache) {
  try {
    const html = await postAnalyzer(sentence.text);
    cache.set(sentence.text, parseViennaSequence(html));
  } catch (error) {
    if (sentence.words.length <= 1 || sentence.text.length < 160) {
      console.warn(`Vienna failed for sentence "${sentence.text.slice(0, 80)}": ${error.message}`);
      cache.set(sentence.text, []);
      return;
    }
    const halves = splitLongSentence(sentence, records);
    if (halves.length < 2) {
      console.warn(`Vienna failed for sentence "${sentence.text.slice(0, 80)}": ${error.message}`);
      cache.set(sentence.text, []);
      return;
    }
    await analyseSentenceWithRecords(halves[0], records, cache);
    await sleep(REQUEST_DELAY_MS);
    await analyseSentenceWithRecords(halves[1], records, cache);
    cache.set(sentence.text, [...(cache.get(halves[0].text) || []), ...(cache.get(halves[1].text) || [])]);
  }
}

function splitLongSentence(sentence, records) {
  const mid = sentence.start + Math.floor((sentence.end - sentence.start) / 2);
  return [
    makeSentence(records, sentence.start, mid),
    makeSentence(records, mid, sentence.end),
  ].filter(Boolean);
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
        'User-Agent': 'UquReader import-mari-book-vienna.js',
      },
      timeout: REQUEST_TIMEOUT_MS,
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        if (response.statusCode && response.statusCode >= 400) {
          reject(new Error(`Vienna analyzer returned HTTP ${response.statusCode}`));
          return;
        }
        resolve(Buffer.concat(chunks).toString('utf8'));
      });
    });
    request.on('timeout', () => request.destroy(new Error('Vienna analyzer request timed out')));
    request.on('error', reject);
    request.end(body);
  });
}

function parseViennaSequence(html) {
  const result = [];
  const cells = html.split('<!--NEXTWORD-->');
  for (const cell of cells) {
    const variants = [...cell.matchAll(/<table>([\s\S]*?)<\/table>/g)].map((match) => match[1]);
    const item = { word: '', variants: [] };
    for (const variant of variants) {
      const rawWord = stripTags(readRow(variant, 'headword')).replace(/<!--.*?-->/g, '').trim().toLowerCase();
      const word = readFirstWord(rawWord);
      if (!word) continue;
      const segments = readCells(variant, 'div').map(stripTags).map(cleanCell).filter(Boolean);
      const entry = {
        lemma: segments[0] || word,
        segments,
        gloss: readCells(variant, 'gloss').map(stripTags).map(cleanCell),
        pos: readCells(variant, 'pos').map(stripTags).map(cleanCell),
      };
      item.word = item.word || word;
      item.variants.push(entry);
    }
    if (item.word) result.push(item);
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
  return String(value || '')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ');
}

function cleanCell(value) {
  return String(value || '').trim();
}

function readFirstWord(value) {
  const match = String(value || '').match(/\p{Script=Cyrillic}+(?:[-']\p{Script=Cyrillic}+)*/u);
  return match ? match[0].toLowerCase() : '';
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
