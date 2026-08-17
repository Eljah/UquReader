#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const jsonlPath = process.argv[2] || path.join(root, 'android-app', 'src', 'main', 'assets', 'elnet_puncheryshte.vienna.ttmorph.jsonl');
const lexiconDir = process.argv[3] || path.join(root, '.codex', 'mari', 'uniparser');

const translations = loadTranslations(lexiconDir);
let variants = 0;
let translated = 0;

const output = [];
for (const line of fs.readFileSync(jsonlPath, 'utf8').split(/\r?\n/)) {
  if (!line.trim()) continue;
  const row = JSON.parse(line);
  if (Array.isArray(row.analyses)) {
    for (const variant of row.analyses) {
      variants++;
      const found = lookupVariantTranslations(variant, translations);
      variant.translations = found;
      if (found.length) translated++;
    }
    if ((!Array.isArray(row.translations) || row.translations.length === 0) && row.analyses.length > 0) {
      const firstTranslations = row.analyses[0].translations || [];
      if (firstTranslations.length && !firstTranslations.some((value) => value === 'не найдено в словаре Vienna')) {
        row.translations = firstTranslations;
      }
    }
  }
  output.push(JSON.stringify(row));
}

fs.writeFileSync(jsonlPath, output.join('\n') + '\n', 'utf8');
console.log(`Enriched ${translated} / ${variants} Vienna analysis variants with lemma translations in ${jsonlPath}`);

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
      const values = trans.split(/\s*[,;]\s*/).map((value) => value.trim()).filter(Boolean).slice(0, 6);
      if (!values.length) continue;
      const current = result.get(key) || [];
      for (const value of values) {
        if (!current.includes(value)) current.push(value);
      }
      result.set(key, current.slice(0, 8));
    }
  }
  return result;
}

function lookupVariantTranslations(variant, dictionary) {
  const candidates = [
    variant.lemma,
    ...derivedDictionaryCandidates(variant.lemma),
    ...(Array.isArray(variant.segments) ? variant.segments.slice(0, 1) : []),
    ...(Array.isArray(variant.segments) ? derivedDictionaryCandidates(variant.segments[0]) : []),
  ].map(normalizeLexeme).filter(Boolean);
  for (const candidate of candidates) {
    const found = dictionary.get(candidate);
    if (found && found.length) return found;
  }
  const lexicalGloss = firstLexicalGloss(variant);
  if (lexicalGloss) return [lexicalGloss];
  if (variant.lemma === '***' || (variant.gloss || []).includes('***')) {
    return ['не найдено в словаре Vienna'];
  }
  if ((variant.gloss || []).includes('[X]')) {
    return ['словарный перевод Vienna не указан'];
  }
  return ['словарный перевод не найден'];
}

function derivedDictionaryCandidates(value) {
  const lower = normalizeLexeme(value);
  const result = [];
  if (!lower) return result;
  if (lower.includes('[') || lower.includes(']')) {
    const withoutMarks = lower.replace(/[\[\]]/g, '');
    const withoutOptional = lower.replace(/\[[^\]]+\]/g, '');
    result.push(withoutMarks, withoutOptional);
    result.push(...derivedDictionaryCandidates(withoutMarks));
    result.push(...derivedDictionaryCandidates(withoutOptional));
  }
  result.push(...verbInfinitiveCandidates(lower));
  for (const suffix of ['ыше', 'ше']) {
    if (lower.endsWith(suffix)) {
      result.push(`${lower.slice(0, -suffix.length)}аш`);
    }
  }
  if (lower.endsWith('ы') && lower.length > 3) {
    result.push(`${lower.slice(0, -1)}аш`);
  }
  return [...new Set(result.filter(Boolean))];
}

function verbInfinitiveCandidates(lower) {
  const result = [
    `${lower}аш`,
    `${lower}яш`,
  ];
  if (lower.endsWith('йы') && lower.length > 2) {
    result.push(`${lower.slice(0, -2)}яш`);
  }
  if (lower.endsWith('й') && lower.length > 1) {
    result.push(`${lower.slice(0, -1)}яш`);
  }
  if (/[ыеа]$/u.test(lower) && lower.length > 1) {
    result.push(`${lower.slice(0, -1)}аш`);
  }
  return result;
}

function firstLexicalGloss(variant) {
  for (const gloss of variant.gloss || []) {
    const value = cleanViennaGloss(gloss);
    if (!value || value.startsWith('-') || value === '***' || value === '[X]') continue;
    if (/^(1|2|3)(SG|PL)$/i.test(value)) continue;
    return value;
  }
  return '';
}

function cleanViennaGloss(value) {
  return String(value || '')
    .replace(/.*class="english">/g, '')
    .replace(/<[^>]*>/g, '')
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

function readField(block, name) {
  const match = block.match(new RegExp(`(?:^|\\n)\\s*${name}:\\s*(.+)`));
  return match ? match[1].trim() : '';
}

function normalizeLexeme(value) {
  return String(value || '').toLowerCase().replace(/\s+/g, ' ').trim();
}
