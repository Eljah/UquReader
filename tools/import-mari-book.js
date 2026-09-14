#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const input = process.argv[2] || path.join(root, '.codex', 'mari', 'pdfjs-text.repaired.txt');
const output = process.argv[3] || path.join(root, 'android-app', 'src', 'main', 'assets', 'elnet_puncheryshte.ttmorph.jsonl');
const lexiconDir = process.argv[4] || path.join(root, '.codex', 'mari', 'uniparser');
const hfstLookupPath = process.argv[5] || path.join(root, '.codex', 'mari', 'mhr-hfst-lookup.tsv');
const ENABLE_OCR_NORMALIZATION = process.env.MARI_ENABLE_OCR_NORMALIZATION === '1';
const ENABLE_LINE_SPLIT_REPAIR = process.env.MARI_ENABLE_LINE_SPLIT_REPAIR === '1';
const ENABLE_PAGE_AWARE = process.env.MARI_DISABLE_PAGE_AWARE !== '1';

const MARI_WORD = /[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+(?:[-'][А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+)*/u;
const TOKEN = /[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+(?:[-'][А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+)*|[A-Za-z]+|\d+|[^\s]/gu;

const PARTICLES = new Set(['да', 'ден', 'деч', 'же', 'гын', 'мо', 'огыл', 'ат', 'гына']);
const CONJUNCTIONS = new Set(['да', 'но', 'але', 'гынат', 'манын']);
const PRONOUNS = new Set(['мый', 'тый', 'тудо', 'ме', 'те', 'нуно', 'кушто', 'кузе', 'кунам', 'мо', 'молан']);

// Shallow Eastern Mari model aligned with GiellaLT lang-mhr tag names:
// https://github.com/giellalt/lang-mhr/blob/main/src/fst/morphology/root.lexc
const SUFFIX_MODEL = [
  { code: 'Pl', forms: ['влак', 'шамыч'], pos: 'N' },
  { code: 'PxSg3', forms: ['ыже', 'ыжо', 'ыжӧ', 'же', 'жо', 'жӧ'], pos: 'N' },
  { code: 'Cmpr', forms: ['ла', 'ле'], pos: 'N', minBase: 4 },
  { code: 'Dat', forms: ['лан'], pos: 'N' },
  { code: 'Com', forms: ['дене', 'ден'], pos: 'N' },
  { code: 'Ine', forms: ['ыште', 'ышто', 'ште', 'што'], pos: 'N' },
  { code: 'Ill', forms: ['ышке', 'ышко', 'шке', 'шко'], pos: 'N' },
  { code: 'Lat', forms: ['еш'], pos: 'N' },
  { code: 'Abe', forms: ['деч'], pos: 'N' },
  { code: 'Gen', forms: ['ын', 'н'], pos: 'N' },
  { code: 'Acc', forms: ['ым', 'ем'], pos: 'N' },
  { code: 'Inf', forms: ['аш'], pos: 'V' },
  { code: 'PtcpAct', forms: ['ыше', 'ше'], pos: 'V' },
  { code: 'Prt1', forms: ['ышым', 'ышыч', 'ышна', 'ышда', 'ышт', 'ыш'], pos: 'V' },
  { code: 'Prs', forms: ['ам', 'ем', 'ат', 'ет', 'еш', 'ена', 'еда', 'ыт'], pos: 'V' },
];

function main() {
  const translations = loadTranslations(lexiconDir);
  const hfst = loadHfstLookup(hfstLookupPath);
  const raw = fs.readFileSync(input, 'utf8');
  const rawTokens = ENABLE_PAGE_AWARE
    ? tokenizePageAwareBook(raw, hfst, translations)
    : tokenize(cleanOcr(raw));
  const tokens = ENABLE_LINE_SPLIT_REPAIR ? repairLineSplitWords(rawTokens, hfst, translations) : rawTokens;
  const records = tokens.map((token) => toRecord(token, translations, hfst));
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, records.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8');
  const words = records.filter((record) => MARI_WORD.test(record.surface)).length;
  const unique = new Set(records.filter((record) => MARI_WORD.test(record.surface)).map((record) => record.surface.toLowerCase())).size;
  const translated = records.filter((record) => record.translations && record.translations.length).length;
  console.log(`Wrote ${records.length} tokens, ${words} Mari-like words, ${unique} unique word forms, ${translated} translated tokens to ${output}`);
}

function tokenizePageAwareBook(raw, hfst, translations) {
  const pages = raw.split('\f')
    .map((text, index) => toBookPage(text, index + 1))
    .filter((page) => page.lines.length > 0);
  const titlePage = pages.findIndex((page) => page.lines.some((line) => /ЭЛНЕТ\s+ПӰНЧЕРЫШТЕ/.test(line)));
  const storyPage = pages.findIndex((page) => page.lines.some((line) => /^\s*Шошо[.!]/.test(line)));
  const start = titlePage >= 0 ? titlePage : Math.max(0, storyPage);
  const bodyPages = pages.slice(start).map((page, index) => ({
    ...page,
    pageIndex: index,
    mainLines: [],
    footnotes: [],
  }));

  for (const page of bodyPages) {
    splitPageLines(page);
  }
  repairPageBoundarySplits(bodyPages, hfst, translations);

  const tokens = [];
  for (const page of bodyPages) {
    tokens.push(...tokenizePageText(linesToParagraphText(page.mainLines), page, 'body', ''));
    for (const footnote of page.footnotes) {
      tokens.push(...tokenizePageText(footnote.text, page, 'footnote', footnote.id));
    }
  }
  return tokens;
}

function toBookPage(text, pdfPage) {
  const rawLines = text.replace(/\r\n/g, '\n').split('\n');
  const lines = rawLines
    .map(cleanPdfLine)
    .filter(Boolean)
    .filter((line) => !/^Image too small/i.test(line) && !/^Line cannot/i.test(line))
    .filter((line) => cyrillicRatio(line) >= 0.35 || /^[\d\s.,:;—-]+$/.test(line) || /[.!?…»"]$/.test(line));
  return {
    pdfPage,
    sourcePage: printedPageNumber(lines) || pdfPage,
    lines,
  };
}

function cleanPdfLine(line) {
  return String(line || '')
    .replace(/[|`©®™•■□]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function printedPageNumber(lines) {
  for (let i = lines.length - 1; i >= Math.max(0, lines.length - 4); i--) {
    const match = String(lines[i] || '').match(/^\s*(\d{1,3})\s*$/);
    if (match) return Number(match[1]);
  }
  return 0;
}

function splitPageLines(page) {
  let footnoteNumber = 0;
  for (const line of page.lines) {
    if (/^\d{1,3}$/.test(line)) {
      continue;
    }
    const footnote = line.match(/^\*+\s*(.+)$/);
    if (footnote) {
      footnoteNumber++;
      page.footnotes.push({
        id: `${page.sourcePage}.${footnoteNumber}`,
        text: normalizeFootnoteText(footnote[1]),
      });
      continue;
    }
    page.mainLines.push(line);
  }
}

function normalizeFootnoteText(text) {
  return String(text || '')
    .replace(/Л\s+ы\s+с\s+т\s+а\s+н\s+п\s+ӱ\s+н\s+ч\s+ӧ/gi, 'Лыстан пӱнчӧ')
    .replace(/\s+([.,:;!?…])/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

function repairPageBoundarySplits(pages, hfst, translations) {
  for (let i = 0; i < pages.length - 1; i++) {
    const current = pages[i];
    const next = pages[i + 1];
    const leftIndex = lastMainLineIndex(current);
    const rightIndex = firstMainLineIndex(next);
    if (leftIndex < 0 || rightIndex < 0) continue;
    const leftLine = current.mainLines[leftIndex];
    const rightLine = next.mainLines[rightIndex];
    const leftMatch = leftLine.match(new RegExp(`^(.*?)([А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]{2,})([-‐‑‒–—]+)\\s*$`, 'u'));
    const rightMatch = rightLine.match(new RegExp(`^\\s*([А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]{2,})(.*)$`, 'u'));
    if (!leftMatch || !rightMatch) continue;
    const chosen = chooseJoinedWord(leftMatch[2], rightMatch[1], hfst, translations)
      || chooseHyphenatedBoundaryWord(leftMatch[2], rightMatch[1], hfst, translations);
    if (!chosen) continue;
    current.mainLines[leftIndex] = `${leftMatch[1]}${matchCase(leftMatch[2], chosen.toLowerCase())}`;
    let remainder = rightMatch[2].trimStart();
    const punctuation = remainder.match(/^([.,:;!?…]+)(.*)$/u);
    if (punctuation) {
      current.mainLines[leftIndex] += punctuation[1];
      remainder = punctuation[2].trimStart();
    }
    if (remainder) {
      next.mainLines[rightIndex] = remainder;
    } else {
      next.mainLines.splice(rightIndex, 1);
    }
  }
}

function chooseHyphenatedBoundaryWord(left, right, hfst, translations) {
  const leftKnown = hasAnalysisOrTranslation(left.toLowerCase(), hfst, translations);
  const rightKnown = hasAnalysisOrTranslation(right.toLowerCase(), hfst, translations)
    || analyze(right).includes('+');
  if (!leftKnown || !rightKnown) {
    return '';
  }
  return `${left}-${right}`.toLowerCase();
}

function lastMainLineIndex(page) {
  for (let i = page.mainLines.length - 1; i >= 0; i--) {
    if (page.mainLines[i]) return i;
  }
  return -1;
}

function firstMainLineIndex(page) {
  for (let i = 0; i < page.mainLines.length; i++) {
    if (page.mainLines[i]) return i;
  }
  return -1;
}

function linesToParagraphText(lines) {
  const paragraphs = [];
  let current = '';
  for (const line of lines) {
    if (!line) continue;
    if (startsNewParagraph(line, current)) {
      if (current) paragraphs.push(current);
      current = line;
    } else {
      current = current ? `${current} ${line}` : line;
    }
  }
  if (current) paragraphs.push(current);
  return paragraphs.join('\n\n');
}

function startsNewParagraph(line, current) {
  if (!current) return false;
  if (/^[—–]/.test(line)) return true;
  if (/^\d{1,2}$/.test(line)) return true;
  if (/^[А-ЯЁӒӦӰҤӸ\s]+$/.test(line) && line.length < 48) return true;
  return false;
}

function tokenizePageText(text, page, role, footnoteId) {
  return tokenize(text).map((token, index) => ({
    ...token,
    prefix: index === 0 && role === 'footnote' ? '' : token.prefix,
    pageIndex: page.pageIndex,
    sourcePage: page.sourcePage,
    role,
    footnoteId,
  }));
}

function cleanOcr(text) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const storyStart = lines.findIndex((line) => /^\s*Шошо[.!]/.test(line));
  const body = storyStart >= 0
    ? ['ЭЛНЕТ ПУНЧЕРЫШТЕ', '', ...lines.slice(storyStart)]
    : lines.slice(Math.max(0, lines.findIndex((line) => /ЭЛНЕТ\s+ПУНЧЕРЫШТЕ/.test(line))));
  return body
    .map((line) => line.replace(/[|`©®™•■□]+/g, ' ').replace(/\s+/g, ' ').trim())
    .filter((line) => line && !/^Image too small/i.test(line) && !/^Line cannot/i.test(line))
    .filter((line) => !/^[\d\s.,:;—-]+$/.test(line))
    .filter((line) => cyrillicRatio(line) >= 0.35 || /[.!?…»"]$/.test(line))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n');
}

function cyrillicRatio(line) {
  const letters = [...line].filter((char) => /\p{L}/u.test(char));
  if (letters.length === 0) return 0;
  const cyrillic = letters.filter((char) => /[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]/u.test(char));
  return cyrillic.length / letters.length;
}

function tokenize(text) {
  const result = [];
  let last = 0;
  for (const match of text.matchAll(TOKEN)) {
    result.push({ prefix: text.slice(last, match.index), surface: match[0] });
    last = match.index + match[0].length;
  }
  return result;
}

function repairLineSplitWords(tokens, hfst, translations) {
  const result = [];
  const repairs = [];
  for (let i = 0; i < tokens.length; i++) {
    const current = tokens[i];
    const next = tokens[i + 1];
    if (!next || !hasLineBreak(next.prefix) || !MARI_WORD.test(current.surface) || !MARI_WORD.test(next.surface)) {
      result.push(current);
      continue;
    }
    const repaired = chooseJoinedWord(current.surface, next.surface, hfst, translations);
    if (!repaired) {
      result.push(current);
      continue;
    }
    result.push({
      prefix: current.prefix,
      surface: matchCase(current.surface, repaired),
    });
    repairs.push(`${current.surface}\\n${next.surface} -> ${repaired}`);
    i++;
  }
  if (repairs.length) {
    console.log(`Repaired ${repairs.length} line-split OCR words`);
    for (const repair of repairs.slice(0, 20)) {
      console.log(`  ${repair}`);
    }
    if (repairs.length > 20) {
      console.log(`  ... ${repairs.length - 20} more`);
    }
  }
  return result;
}

function hasLineBreak(prefix) {
  return /[\r\n]/.test(prefix || '');
}

function chooseJoinedWord(left, right, hfst, translations) {
  const joined = `${left}${right}`.toLowerCase();
  const candidates = variants(joined);
  const analysed = candidates
    .map((candidate) => ({ candidate, entry: hfst.get(candidate) }))
    .filter((item) => item.entry && item.entry.analysis && !item.entry.analysis.includes('+?'));
  if (!analysed.length) {
    return chooseDictionaryJoinedWord(joined, candidates, translations);
  }
  analysed.sort((a, b) => joinedCandidateScore(joined, b.candidate, b.entry.analysis)
      - joinedCandidateScore(joined, a.candidate, a.entry.analysis));
  return analysed[0].entry.surface || analysed[0].candidate;
}

function chooseDictionaryJoinedWord(joined, candidates, translations) {
  const analysed = candidates
    .map((candidate) => ({ candidate, lemma: dictionaryLemmaForSurface(candidate, translations) }))
    .filter((item) => item.lemma);
  if (!analysed.length) {
    return '';
  }
  analysed.sort((a, b) => joinedCandidateScore(joined, b.candidate, `${b.lemma}+V`)
      - joinedCandidateScore(joined, a.candidate, `${a.lemma}+V`));
  return analysed[0].candidate;
}

function dictionaryLemmaForSurface(surface, translations) {
  const lower = surface.toLowerCase();
  if (translations.has(normalizeLexeme(lower))) {
    return lower;
  }
  for (const suffix of ['ыше', 'ше']) {
    if (!lower.endsWith(suffix)) {
      continue;
    }
    const stem = lower.slice(0, -suffix.length);
    const infinitive = `${stem}аш`;
    if (translations.has(normalizeLexeme(infinitive))) {
      return infinitive;
    }
  }
  return '';
}

function variants(word) {
  const chars = Array.from(word);
  const mutable = chars.map((char) => replacements(char));
  const limit = mutable.reduce((count, options) => count * options.length, 1);
  if (limit > 512) return [word];
  const result = [];
  function walk(index, built) {
    if (index === mutable.length) {
      result.push(built);
      return;
    }
    for (const option of mutable[index]) walk(index + 1, built + option);
  }
  walk(0, '');
  return result;
}

function replacements(char) {
  switch (char) {
    case 'у': return ['у', 'ӱ'];
    case 'ӱ': return ['ӱ', 'у'];
    case 'о': return ['о', 'ӧ'];
    case 'ӧ': return ['ӧ', 'о'];
    case 'н': return ['н', 'ҥ'];
    case 'ҥ': return ['ҥ', 'н'];
    default: return [char];
  }
}

function joinedCandidateScore(ocr, surface, analysis) {
  let score = 1000;
  if (surface === ocr) score += 10;
  if (/[ӱӧҥ]/.test(surface)) score += 35;
  if (analysis.includes('+V')) score += 8;
  if (analysis.includes('+N')) score += 3;
  return score - Math.abs(surface.length - ocr.length);
}

function toRecord(token, translations, hfst) {
  const normalizedSurface = normalizeSurface(token.surface, hfst, translations);
  const hfstEntry = hfst.get(normalizedSurface.toLowerCase());
  const surface = hfstEntry && hfstEntry.surface ? matchCase(token.surface, hfstEntry.surface) : token.surface;
  const restoredSurface = normalizedSurface !== token.surface ? matchCase(token.surface, normalizedSurface) : surface;
  const analysis = completeLookupAnalysis(restoredSurface, hfstEntry && hfstEntry.analysis ? hfstEntry.analysis : analyze(restoredSurface));
  const lemma = analysis.includes('+') ? analysis.split('+')[0].toLowerCase() : token.surface.toLowerCase();
  return {
    prefix: token.prefix,
    surface: restoredSurface,
    analysis,
    translations: lookupTranslations(translations, lemma, restoredSurface),
    pageIndex: token.pageIndex,
    sourcePage: token.sourcePage,
    role: token.role,
    footnoteId: token.footnoteId,
  };
}

function normalizeSurface(surface, hfst, translations) {
  if (ENABLE_OCR_NORMALIZATION) {
    const known = normalizeKnownOcrWord(surface);
    if (known !== surface) {
      return known;
    }
  }
  const lower = surface.toLowerCase();
  if (hasAnalysisOrTranslation(lower, hfst, translations)) {
    return surface;
  }
  if (!ENABLE_OCR_NORMALIZATION) {
    return surface;
  }
  for (const candidate of stripTrailingOcrNoise(lower)) {
    if (hasAnalysisOrTranslation(candidate, hfst, translations)) {
      return matchCase(surface, candidate);
    }
  }
  return surface;
}

function stripTrailingOcrNoise(lower) {
  const result = [];
  if (lower.endsWith('и') && lower.length > 4) {
    result.push(lower.slice(0, -1));
  }
  return result;
}

function hasAnalysisOrTranslation(surface, hfst, translations) {
  return hfst.has(surface) || translations.has(normalizeLexeme(surface));
}

function normalizeKnownOcrWord(surface) {
  const lower = surface.toLowerCase();
  const replacements = new Map([
    ['удыр', 'ӱдыр'],
    ['удыр-влак', 'ӱдыр-влак'],
    ['удыр-влаклан', 'ӱдыр-влаклан'],
    ['удыр-влакын', 'ӱдыр-влакын'],
    ['удырат', 'ӱдырат'],
    ['удырда', 'ӱдырда'],
    ['удырак', 'ӱдырак'],
    ['удырем', 'ӱдырем'],
    ['удыремлан', 'ӱдыремлан'],
    ['удырет', 'ӱдырет'],
    ['удыржак', 'ӱдыржак'],
    ['удыржат', 'ӱдыржат'],
    ['удырышт', 'ӱдырышт'],
    ['удырлан', 'ӱдырлан'],
    ['удырым', 'ӱдырым'],
    ['удырымат', 'ӱдырымат'],
    ['удырын', 'ӱдырын'],
    ['удыржым', 'ӱдыржым'],
    ['тузла', 'тӱзла'],
    ['тузлана', 'тӱзлана'],
    ['тузланен', 'тӱзланен'],
  ]);
  return replacements.get(lower) || surface;
}

function completeLookupAnalysis(surface, analysis) {
  if (!analysis || !analysis.includes('+')) return analysis;
  const lower = surface.toLowerCase();
  for (const suffix of ['ла', 'ле']) {
    if (!lower.endsWith(suffix)) continue;
    const base = lower.slice(0, -suffix.length);
    if (base.length < 4) continue;
    const parts = analysis.split('+');
    if (parts.length >= 3 && parts[0] === base && parts[1] === 'N' && parts.includes('Sg')
        && !parts.some((part) => part === 'Cmpr' || part.startsWith('Cmpr('))) {
      return `${parts.join('+')}+Cmpr(${suffix})`;
    }
  }
  return analysis;
}

function loadHfstLookup(file) {
  const result = new Map();
  if (!fs.existsSync(file)) return result;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const [ocr, surface, analysis] = line.split('\t');
    if (ocr && surface && analysis && !result.has(ocr)) {
      result.set(ocr, { surface, analysis });
    }
  }
  return result;
}

function loadTranslations(dir) {
  const result = new Map();
  if (!fs.existsSync(dir)) return result;
  for (const file of fs.readdirSync(dir).filter((name) => /^mhr_lexemes_.*\.txt$/i.test(name))) {
    const blocks = fs.readFileSync(path.join(dir, file), 'utf8').split(/\n(?=-lexeme)/);
    for (const block of blocks) {
      const lex = readField(block, 'lex');
      const trans = readField(block, 'trans_ru');
      if (!lex || !trans) continue;
      const key = normalizeLexeme(lex);
      if (!key) continue;
      const values = trans.split(/\s*[,;]\s*/).map((value) => value.trim()).filter(Boolean).slice(0, 4);
      if (!values.length) continue;
      const current = result.get(key) || [];
      for (const value of values) {
        if (!current.includes(value)) current.push(value);
      }
      result.set(key, current.slice(0, 6));
    }
  }
  return result;
}

function readField(block, name) {
  const match = block.match(new RegExp(`(?:^|\\n)\\s*${name}:\\s*(.+)`));
  return match ? match[1].trim() : '';
}

function normalizeLexeme(value) {
  return value.toLowerCase().replace(/\s+/g, ' ').trim();
}

function lookupTranslations(translations, lemma, surface) {
  const candidates = [lemma, `${lemma}аш`, surface.toLowerCase()];
  for (const candidate of candidates) {
    const found = translations.get(normalizeLexeme(candidate));
    if (found && found.length) return found;
  }
  return [];
}

function matchCase(original, restored) {
  if (original.length > 1 && original === original.toLocaleUpperCase('ru-RU')) {
    return restored.toLocaleUpperCase('ru-RU');
  }
  const first = original.codePointAt(0);
  if (first != null && original[0] === original[0].toLocaleUpperCase('ru-RU')) {
    return restored.charAt(0).toLocaleUpperCase('ru-RU') + restored.slice(1);
  }
  return restored;
}

function analyze(surface) {
  const lower = surface.toLowerCase();
  const isHeading = surface.length > 2 && surface === surface.toLocaleUpperCase('ru-RU');
  if (/^[A-Za-z]+$/.test(surface)) return 'Latin';
  if (/^\d+$/.test(surface)) return 'NR';
  if (!MARI_WORD.test(surface)) return 'Sign';
  if (CONJUNCTIONS.has(lower)) return `${lower}+CC`;
  if (PRONOUNS.has(lower)) return `${lower}+Pron+Sg+Nom`;
  if (PARTICLES.has(lower)) return `${lower}+Pcle`;

  const features = [];
  let base = lower;
  let pos = 'N';
  for (const rule of SUFFIX_MODEL) {
    if (isHeading && rule.pos === 'V') continue;
    const minBase = rule.minBase ?? 2;
    const form = rule.forms.find((candidate) => base.length > candidate.length + minBase && base.endsWith(candidate));
    if (!form) continue;
    pos = rule.pos;
    features.push(`${rule.code}(${form})`);
    base = base.slice(0, -form.length).replace(/[-']+$/u, '');
    if (rule.pos === 'V') break;
  }

  if (features.length === 0) {
    features.push('Sg', 'Nom');
  } else if (pos === 'N') {
    if (!features.some((feature) => /^Pl\(/.test(feature))) {
      features.unshift('Sg');
    }
    if (!features.some((feature) => /^(Gen|Acc|Dat|Com|Ine|Ill|Lat|Abe|Cmpr)\(/.test(feature))) {
      features.push('Nom');
    }
  }
  return `${base}+${pos}+${features.join('+')}`;
}

main();
