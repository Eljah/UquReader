#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const input = process.argv[2] || path.join(root, '.codex', 'mari', 'ocr-full.txt');
const lookupOutput = process.argv[3] || path.join(root, '.codex', 'mari', 'mhr-hfst-lookup.tsv');
const candidateOutput = process.argv[4] || path.join(root, '.codex', 'mari', 'mhr-hfst-candidates.txt');
const hfstRawOutput = process.argv[5] || path.join(root, '.codex', 'mari', 'mhr-hfst-raw.txt');

const MARI_WORD = /[А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+(?:[-'][А-Яа-яЁёӒӓӦӧӰӱҤҥӸӹ]+)*/gu;

if (process.argv[2] === '--parse') {
  parseLookup(
    process.argv[3] || candidateOutput,
    process.argv[4] || hfstRawOutput,
    process.argv[5] || lookupOutput
  );
} else {
  writeCandidates(input, candidateOutput);
}

function writeCandidates(source, output) {
  const text = fs.readFileSync(source, 'utf8').toLowerCase();
  const pairs = new Map();
  for (const match of text.matchAll(MARI_WORD)) {
    const word = match[0];
    if (!pairs.has(word)) pairs.set(word, new Set());
    for (const candidate of variants(word)) {
      pairs.get(word).add(candidate);
    }
  }
  for (const joined of lineSplitWords(text)) {
    if (!pairs.has(joined)) pairs.set(joined, new Set());
    for (const candidate of variants(joined)) {
      pairs.get(joined).add(candidate);
    }
  }
  const lines = [];
  for (const [ocr, candidates] of pairs) {
    for (const candidate of candidates) {
      lines.push(`${ocr}\t${candidate}`);
    }
  }
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, lines.join('\n') + '\n', 'utf8');
  console.log(`Wrote ${lines.length} candidates for ${pairs.size} OCR word forms to ${output}`);
}

function lineSplitWords(text) {
  const result = new Set();
  const pattern = /([а-яёӓӧӱҥӹ]+(?:[-'][а-яёӓӧӱҥӹ]+)*)[^\S\r\n]*\r?\n[^\S\r\n]*([а-яёӓӧӱҥӹ]+(?:[-'][а-яёӓӧӱҥӹ]+)*)/giu;
  for (const match of text.matchAll(pattern)) {
    const joined = `${match[1]}${match[2]}`;
    if (joined.length >= 6 && joined.length <= 36) {
      result.add(joined);
    }
  }
  return result;
}

function variants(word) {
  const chars = Array.from(word);
  const mutable = chars.map((char) => replacements(char));
  const limit = mutable.reduce((count, options) => count * options.length, 1);
  if (limit > 256) return [word];
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

function parseLookup(candidateFile, rawFile, output) {
  const candidatePairs = fs.readFileSync(candidateFile, 'utf8').split(/\r?\n/)
    .filter(Boolean)
    .map((line) => line.split('\t'));
  const byCandidate = new Map();
  for (const [ocr, candidate] of candidatePairs) {
    if (!byCandidate.has(candidate)) byCandidate.set(candidate, []);
    byCandidate.get(candidate).push(ocr);
  }

  const best = new Map();
  for (const line of fs.readFileSync(rawFile, 'utf8').split(/\r?\n/)) {
    if (!line.includes('\t')) continue;
    const [surface, analysis, weight] = line.split('\t');
    if (!surface || !analysis || analysis.includes('+?')) continue;
    const normalized = normalizeAnalysis(analysis);
    if (!normalized) continue;
    for (const ocr of byCandidate.get(surface) || []) {
      const current = best.get(ocr);
      const score = candidateScore(ocr, surface, normalized, weight);
      if (!current || score > current.score) {
        best.set(ocr, { surface, analysis: normalized, score });
      }
    }
  }

  const lines = Array.from(best.entries())
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([ocr, value]) => `${ocr}\t${value.surface}\t${value.analysis}`);
  fs.writeFileSync(output, lines.join('\n') + '\n', 'utf8');
  console.log(`Wrote ${lines.length} HFST analyses to ${output}`);
}

function normalizeAnalysis(raw) {
  const parts = raw.split('+').filter(Boolean);
  if (parts.length < 2) return '';
  const lemma = parts[0];
  let pos = '';
  const features = [];
  for (const part of parts.slice(1)) {
    const mapped = mapTag(part);
    if (!mapped) continue;
    if (['N', 'V', 'A', 'Adv', 'Pron', 'CC', 'Pcle', 'Num'].includes(mapped) && !pos) {
      pos = mapped === 'A' ? 'Adj' : mapped;
    } else if (!['N', 'V', 'A', 'Adj', 'Adv', 'Pron', 'CC', 'Pcle', 'Num'].includes(mapped) && !features.includes(mapped)) {
      features.push(mapped);
    }
  }
  if (!pos) return '';
  return `${lemma}+${pos}${features.length ? '+' + features.join('+') : ''}`;
}

function mapTag(tag) {
  const clean = tag.replace(/^Hom\d+$/, '').replace(/^Sem\/.+$/, '').replace(/^Der\/.+$/, '');
  const map = {
    N: 'N', V: 'V', A: 'Adj', Adv: 'Adv', Pron: 'Pron', CC: 'CC', Pcle: 'Pcle', Num: 'Num',
    Sg: 'Sg', Pl: 'Pl', Nom: 'Nom', Gen: 'Gen', Acc: 'Acc', Dat: 'Dat', Ine: 'Ine', Ill: 'Ill',
    Lat: 'Lat', Com: 'Com', Abe: 'Abe', PxSg3: 'PxSg3', Prs: 'Prs', Prt1: 'Prt1', Inf: 'Inf',
  };
  return map[clean] || '';
}

function candidateScore(ocr, surface, analysis, weight) {
  let score = 1000;
  if (ocr === surface) score += 50;
  if (/[ӱӧҥ]/.test(surface)) score += 25;
  if (analysis.includes('+N')) score += 5;
  if (weight === '0') score += 1;
  return score - Math.abs(surface.length - ocr.length);
}
