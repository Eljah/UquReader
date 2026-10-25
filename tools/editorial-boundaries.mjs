import {readFileSync, writeFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

const letters = /\p{L}/u;
const unknown = t => ['', 'Unknown', 'Error'].includes(t.analysis || '') || /\+\*\*\*\||\+\?/.test(t.analysis);
const word = t => letters.test(t.surface);
const refs = rows => [...new Map(rows.flatMap(r => r.document.sourceRefs || [])
  .map(r => [`${r.sourceId}:${r.page}`, r])).values()];

// Every boundary is inventoried, even if both sides have valid analyses.
// Flags prioritize inspection, never authorize deleting punctuation or joining words.
export function scanBoundaries(snapshot) {
  const rows = [...snapshot.sentences].sort((a, b) => a.ordinal - b.ordinal);
  const bracketIssues = [];
  const stack = [];
  const closing = {')': '(', ']': '[', '}': '{'};
  for (let rowIndex = 0; rowIndex < rows.length; rowIndex++) {
    let offset = 0;
    for (const char of rows[rowIndex].document.text) {
      const location = {sentenceId: rows[rowIndex].id, ordinal: rows[rowIndex].ordinal,
        rowIndex, offset, char};
      if ('([{'.includes(char)) stack.push(location);
      if (Object.hasOwn(closing, char)) {
        if (stack.at(-1)?.char === closing[char]) stack.pop();
        else bracketIssues.push({...location, reason: 'unmatched-closing-bracket'});
      }
      offset++;
    }
  }
  bracketIssues.push(...stack.map(location => ({...location, reason: 'unmatched-opening-bracket'})));
  const issuesByRow = new Map();
  for (const issue of bracketIssues) {
    if (!issuesByRow.has(issue.rowIndex)) issuesByRow.set(issue.rowIndex, []);
    issuesByRow.get(issue.rowIndex).push(issue);
  }
  const boundaries = [];
  for (let i = 0; i + 1 < rows.length; i++) {
    let start = i, end = i + 1;
    while (start > 0 && !letters.test(rows[start].document.text)) start--;
    while (end + 1 < rows.length && !letters.test(rows[end].document.text)) end++;
    const window = rows.slice(start, end + 1);
    const left = rows[start].document.tokens.findLast(word);
    const right = rows[end].document.tokens.find(word);
    const reasons = [];
    const windowBracketIssues = window.flatMap((_, n) => issuesByRow.get(start + n) || []);
    for (const issue of windowBracketIssues) if (!reasons.includes(issue.reason)) reasons.push(issue.reason);
    const a = rows[i].document, b = rows[i + 1].document;
    if (!letters.test(a.text) || !letters.test(b.text)) reasons.push('punctuation-only-record');
    if (/^\s*[^\p{L}\p{N}\s]/u.test(b.text)) reasons.push('leading-punctuation');
    if (/^\s*\p{Ll}/u.test(b.text)) reasons.push('lowercase-continuation');
    if (left && right && (unknown(left) || unknown(right))) reasons.push('unknown-edge-word');
    if (/[-\u00ad]\s*[.!?)]*\s*$/u.test(a.text)) reasons.push('possible-hyphenation');
    const pages = d => (d.sourceRefs || []).map(r => `${r.sourceId}:${r.page}`).sort().join('|');
    if (pages(a) !== pages(b)) reasons.push('source-page-transition');
    if (a.tokens.at(-1)?.role !== b.tokens[0]?.role) reasons.push('layout-role-transition');
    const pairText = a.text + b.text;
    if (/[.]\s*[)!]|[!?]\s*[.]|[.!?]\s*[-]\s*\p{Ll}/u.test(pairText)) reasons.push('suspicious-punctuation-sequence');
    boundaries.push({
      id: `${rows[i].id}:${rows[i + 1].id}`,
      pair: [rows[i].id, rows[i + 1].id],
      ordinals: [rows[i].ordinal, rows[i + 1].ordinal],
      window: window.map(r => ({id: r.id, ordinal: r.ordinal, revision: r.revision, text: r.document.text})),
      sourceRefs: refs(window), reasons, bracketIssues: windowBracketIssues,
      edgeWords: [left?.surface ?? null, right?.surface ?? null],
      // This raw concatenation is only a hypothesis; OCR letter repairs need PDF evidence.
      joinHypothesis: left && right ? left.surface.replace(/[-\u00ad]$/u, '') + right.surface : null,
      visualReviewed: false, status: 'pending',
    });
  }
  return {summary: {workId: snapshot.work.id, workRevision: snapshot.work.revision,
    units: rows.length, boundaries: boundaries.length,
    flagged: boundaries.filter(b => b.reasons.length).length,
    expandedWindows: boundaries.filter(b => b.window.length > 2).length,
    unmatchedBrackets: bracketIssues.length,
    visualReviewed: 0, automaticChanges: 0, complete: false}, bracketIssues, boundaries};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) throw new Error('Usage: node tools/editorial-boundaries.mjs SNAPSHOT OUTPUT');
  const report = scanBoundaries(JSON.parse(readFileSync(input, 'utf8')));
  writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report.summary));
}
