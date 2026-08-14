#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const assetsDir = path.join(root, 'android-app', 'src', 'main', 'assets');
const androidGrammar = path.join(root, 'android-app', 'src', 'main', 'java', 'com', 'example', 'ttreader', 'util', 'GrammarResources.java');
const webGrammar = path.join(root, 'web-app', 'src', 'main', 'java', 'com', 'example', 'uqureader', 'webapp', 'reader', 'GrammarCatalog.java');

const discovered = {
  pos: new Set(),
  features: new Set(),
};

for (const file of fs.readdirSync(assetsDir).filter(name => name.endsWith('.ttmorph.jsonl'))) {
  const body = fs.readFileSync(path.join(assetsDir, file), 'utf8');
  for (const line of body.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const record = JSON.parse(line);
    collectAnalysis(record.analysis, discovered);
  }
}

const android = parseAndroidGrammar(fs.readFileSync(androidGrammar, 'utf8'));
const web = parseWebGrammar(fs.readFileSync(webGrammar, 'utf8'));

const failures = [];
checkCoverage('Android POS fallback', discovered.pos, android.pos, failures);
checkCoverage('Android feature fallback', discovered.features, android.features, failures);
checkCoverage('Web POS catalog', discovered.pos, web.pos, failures);
checkCoverage('Web feature catalog', discovered.features, web.features, failures);
checkCompleteness('Web POS catalog', web.posDetails, ['titleTt', 'titleRu'], failures);
checkCompleteness('Web feature catalog', web.featureDetails, ['titleRu', 'titleTt', 'descriptionRu', 'examples'], failures);

console.log(`Discovered POS: ${discovered.pos.size}`);
console.log(`Discovered features: ${discovered.features.size}`);
console.log(`Android POS entries: ${android.pos.size}`);
console.log(`Android feature entries: ${android.features.size}`);
console.log(`Web POS entries: ${web.pos.size}`);
console.log(`Web feature entries: ${web.features.size}`);

if (failures.length) {
  console.error('\nGrammar coverage failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log('Grammar coverage OK: every discovered POS/feature has server and Android metadata.');

function collectAnalysis(analysis, target) {
  if (!analysis) return;
  for (const variant of String(analysis).split(';')) {
    const parts = variant.split('+').map(part => part.trim()).filter(Boolean);
    if (!parts.length) continue;
    const posIndex = parts.length === 1 ? 0 : 1;
    const pos = stripForm(parts[posIndex]);
    if (pos) target.pos.add(pos);
    for (const part of parts.slice(posIndex + 1)) {
      const code = stripForm(part);
      if (code) target.features.add(code);
    }
  }
}

function stripForm(value) {
  return String(value).replace(/\(.*\)$/, '');
}

function parseAndroidGrammar(source) {
  return {
    pos: matchCodes(source, /putPos\("([^"]+)"/g),
    features: matchCodes(source, /putFeature\("([^"]+)"/g),
  };
}

function parseWebGrammar(source) {
  return {
    pos: matchCodes(source, /pos\("([^"]+)"/g),
    features: matchCodes(source, /feature\("([^"]+)"/g),
    posDetails: matchRows(source, /pos\("([^"]+)",\s*"([^"]*)",\s*"([^"]*)"\);/g, ['code', 'titleTt', 'titleRu']),
    featureDetails: matchRows(
      source,
      /feature\("([^"]+)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)"\);/g,
      ['code', 'titleRu', 'titleTt', 'descriptionRu', 'phoneticForms', 'examples']
    ),
  };
}

function matchCodes(source, regex) {
  const result = new Set();
  for (const match of source.matchAll(regex)) result.add(match[1]);
  return result;
}

function matchRows(source, regex, keys) {
  const rows = new Map();
  for (const match of source.matchAll(regex)) {
    const row = {};
    keys.forEach((key, index) => {
      row[key] = match[index + 1] || '';
    });
    rows.set(row.code, row);
  }
  return rows;
}

function checkCoverage(label, expected, actual, failures) {
  const missing = [...expected].filter(code => !actual.has(code)).sort();
  if (missing.length) failures.push(`${label} missing: ${missing.join(', ')}`);
}

function checkCompleteness(label, rows, fields, failures) {
  for (const [code, row] of rows) {
    for (const field of fields) {
      if (!row[field]) failures.push(`${label} ${code} has empty ${field}`);
    }
  }
}
