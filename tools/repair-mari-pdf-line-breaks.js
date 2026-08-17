#!/usr/bin/env node

const fs = require('fs');
const https = require('https');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = process.argv[2] || path.join(root, '.codex', 'mari', 'pdfjs-text.txt');
const output = process.argv[3] || path.join(root, '.codex', 'mari', 'pdfjs-text.repaired.txt');
const reportPath = process.argv[4] || path.join(root, '.codex', 'mari', 'pdf-line-break-repairs.json');
const cachePath = process.argv[5] || path.join(root, '.codex', 'mari', 'vienna-word-cache.json');

const REQUEST_DELAY_MS = Number(process.env.VIENNA_REQUEST_DELAY_MS || 100);
const CONCURRENCY = Number(process.env.VIENNA_CONCURRENCY || 6);
const MAX_JOIN_VARIANTS = 32;
const WORD = '[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+';
const WORD_RE = new RegExp(`^${WORD}$`, 'u');
const CYR_RE = /[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]/u;
const LINE_SPLIT_RE = new RegExp(`(${WORD})([-‐‑‒–—]?[;,.:'"”„“»« ]*)\\n\\s*(${WORD})`, 'gu');

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});

async function main() {
  const text = fs.readFileSync(input, 'utf8');
  const cache = loadCache(cachePath);
  const candidates = collectCandidates(text);
  const uniqueJoined = [...new Set(candidates.flatMap((item) => [
    ...item.joinedVariants.map((variant) => variant.toLowerCase()),
    item.left.toLowerCase(),
    item.right.toLowerCase(),
  ]))];
  console.log(`Found ${candidates.length} line-break candidates, ${uniqueJoined.length} unique forms to validate`);
  await analyseWords(uniqueJoined, cache, cachePath);

  const repairs = [];
  const repaired = text.replace(LINE_SPLIT_RE, (match, left, glue, right, offset) => {
    const originalJoined = `${left}${right}`;
    const joined = chooseJoinedVariant({ left, right, joined: originalJoined, joinedVariants: joinedWordVariants(left, right) }, cache);
    if (!joined) return match;
    const originalJoinedAnalysis = cache[originalJoined.toLowerCase()];
    const joinedAnalysis = cache[joined.toLowerCase()];
    const leftAnalysis = cache[left.toLowerCase()];
    const rightAnalysis = cache[right.toLowerCase()];
    if (!shouldRepair({ left, glue, right, joined: originalJoined, chosenJoined: joined, originalJoinedAnalysis, joinedAnalysis, leftAnalysis, rightAnalysis })) {
      return match;
    }
    repairs.push({
      offset,
      from: match,
      to: matchCase(left, joined.toLowerCase()),
      left,
      glue,
      right,
      joined: originalJoined,
      chosenJoined: joined,
      originalJoinedAnalyses: originalJoinedAnalysis,
      joinedAnalyses: joinedAnalysis,
      leftAnalyses: leftAnalysis,
      rightAnalyses: rightAnalysis,
    });
    return matchCase(left, joined.toLowerCase());
  });

  fs.writeFileSync(output, repaired, 'utf8');
  fs.writeFileSync(reportPath, JSON.stringify({
    input,
    output,
    candidates: candidates.length,
    uniqueJoined: uniqueJoined.length,
    repairs: repairs.length,
    repaired: repairs,
    rejected: candidates
      .filter((item) => !shouldRepair({
        ...item,
        chosenJoined: chooseJoinedVariant(item, cache) || item.joined,
        originalJoinedAnalysis: cache[item.joined.toLowerCase()],
        joinedAnalysis: cache[(chooseJoinedVariant(item, cache) || item.joined).toLowerCase()],
        leftAnalysis: cache[item.left.toLowerCase()],
        rightAnalysis: cache[item.right.toLowerCase()],
      }))
      .slice(0, 200)
      .map((item) => ({
        ...item,
        chosenJoined: chooseJoinedVariant(item, cache) || item.joined,
        joinedAnalyses: cache[(chooseJoinedVariant(item, cache) || item.joined).toLowerCase()] || [],
        leftAnalyses: cache[item.left.toLowerCase()] || [],
        rightAnalyses: cache[item.right.toLowerCase()] || [],
      })),
  }, null, 2) + '\n', 'utf8');
  console.log(`Repaired ${repairs.length} line-break words to ${output}`);
  for (const repair of repairs.slice(0, 40)) {
    console.log(`  ${compact(repair.from)} -> ${repair.to}`);
  }
  if (repairs.length > 40) console.log(`  ... ${repairs.length - 40} more`);
}

function collectCandidates(text) {
  const result = [];
  for (const match of text.matchAll(LINE_SPLIT_RE)) {
    const left = match[1];
    const glue = match[2] || '';
    const right = match[3];
    if (!WORD_RE.test(left) || !WORD_RE.test(right)) continue;
    if (left.length < 2 || right.length < 2) continue;
    if (!CYR_RE.test(left) || !CYR_RE.test(right)) continue;
    const joined = `${left}${right}`;
    result.push({
      offset: match.index,
      left,
      glue,
      right,
      joined,
      joinedVariants: joinedWordVariants(left, right),
      from: match[0],
    });
  }
  return result;
}

function chooseJoinedVariant(item, cache) {
  for (const variant of item.joinedVariants || [item.joined]) {
    if (isUsefulAnalysis(cache[variant.toLowerCase()])) {
      return variant;
    }
  }
  return '';
}

function joinedWordVariants(left, right) {
  const rightVariants = spellingVariants(right.toLowerCase());
  const result = [];
  for (const rightVariant of rightVariants) {
    result.push(`${left}${rightVariant}`);
  }
  return [...new Set(result)].slice(0, MAX_JOIN_VARIANTS);
}

function spellingVariants(word) {
  const chars = Array.from(word);
  const result = [word];
  for (let i = 0; i < chars.length; i++) {
    for (const option of spellingOptions(chars[i]).slice(1)) {
      const copy = chars.slice();
      copy[i] = option;
      result.push(copy.join(''));
      if (result.length >= MAX_JOIN_VARIANTS) return result;
    }
  }
  return result;
}

function spellingOptions(char) {
  switch (char) {
    case '\u0438': return ['\u0438', '\u044b'];
    case '\u044b': return ['\u044b', '\u0438'];
    case '\u0443': return ['\u0443', '\u04f1'];
    case '\u04f1': return ['\u04f1', '\u0443'];
    case '\u043e': return ['\u043e', '\u04e7'];
    case '\u04e7': return ['\u04e7', '\u043e'];
    case '\u043d': return ['\u043d', '\u04a5'];
    case '\u04a5': return ['\u04a5', '\u043d'];
    default: return [char];
  }
}

async function analyseWords(words, cache, cachePath) {
  let next = 0;
  let completed = 0;
  const todo = words.filter((word) => !Object.prototype.hasOwnProperty.call(cache, word));
  console.log(`Vienna word cache has ${Object.keys(cache).length}; ${todo.length} words need analysis`);
  const workers = Array.from({ length: Math.max(1, CONCURRENCY) }, async (_, workerIndex) => {
    await sleep(workerIndex * REQUEST_DELAY_MS);
    for (;;) {
      const index = next++;
      if (index >= todo.length) return;
      const word = todo[index];
      try {
        cache[word] = parseViennaSequence(await postAnalyzer(word));
      } catch (error) {
        console.warn(`Vienna failed for ${word}: ${error.message}`);
        cache[word] = [];
      }
      completed++;
      if (completed % 25 === 0 || completed === todo.length) {
        saveCache(cachePath, cache);
        console.log(`Analysed ${completed} / ${todo.length}`);
      }
      await sleep(REQUEST_DELAY_MS);
    }
  });
  await Promise.all(workers);
  saveCache(cachePath, cache);
}

function isUsefulAnalysis(sequence) {
  if (!Array.isArray(sequence) || sequence.length !== 1) return false;
  const variants = sequence[0].variants || [];
  return variants.some((variant) => {
    const lemma = variant.lemma || '';
    const gloss = variant.gloss || [];
    const pos = variant.pos || [];
    if (!lemma || lemma === '***') return false;
    if (gloss.includes('***')) return false;
    return pos.some((value) => value && value !== '***');
  });
}

function shouldRepair(item) {
  if (!isUsefulAnalysis(item.joinedAnalysis)) return false;
  if (hasExplicitHyphen(item.glue)) return true;
  if (!/^\s*$/u.test(item.glue || '')) return false;
  const originalJoined = String(item.joined || `${item.left || ''}${item.right || ''}`).toLowerCase();
  const chosenJoined = String(item.chosenJoined || item.joined || '').toLowerCase();
  if (chosenJoined && chosenJoined !== originalJoined && !isUsefulAnalysis(item.originalJoinedAnalysis)) {
    return true;
  }
  return isUsefulAnalysis(item.leftAnalysis) && !isUsefulAnalysis(item.rightAnalysis);
}

function hasExplicitHyphen(value) {
  return /[-‐‑‒–—]/u.test(value || '');
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
      item.word = item.word || word;
      item.variants.push({
        lemma: segments[0] || word,
        segments,
        gloss: readCells(variant, 'gloss').map(stripTags).map(cleanCell),
        pos: readCells(variant, 'pos').map(stripTags).map(cleanCell),
      });
    }
    if (item.word) result.push(item);
  }
  return result;
}

function postAnalyzer(inputText) {
  const body = new URLSearchParams({ inField: inputText, dnt: 'dnt' }).toString();
  return new Promise((resolve, reject) => {
    const request = https.request({
      hostname: 'mari-language.univie.ac.at',
      path: '/analyzer.php?int=0',
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'UquReader repair-mari-pdf-line-breaks.js',
      },
      timeout: 90000,
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    });
    request.on('timeout', () => request.destroy(new Error('Vienna analyzer request timed out')));
    request.on('error', reject);
    request.end(body);
  });
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
  const match = String(value || '').match(/[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+(?:[-'][А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+)*/u);
  return match ? match[0].toLowerCase() : '';
}

function loadCache(file) {
  if (!fs.existsSync(file)) return {};
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}

function saveCache(file, cache) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(cache, null, 2) + '\n', 'utf8');
}

function matchCase(original, restored) {
  if (original.length > 1 && original === original.toLocaleUpperCase('ru-RU')) {
    return restored.toLocaleUpperCase('ru-RU');
  }
  if (original[0] === original[0].toLocaleUpperCase('ru-RU')) {
    return restored.charAt(0).toLocaleUpperCase('ru-RU') + restored.slice(1);
  }
  return restored;
}

function compact(value) {
  return String(value).replace(/\s*\n\s*/g, '\\n');
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
