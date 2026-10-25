import assert from 'node:assert/strict';

// A disposition covers the flagged context only, never all spelling on its page.
export function auditWindow(window, {corrections = [], blockers = [], unchanged = []}) {
  const ordinals = new Set(window.text.map(row => row.ordinal));
  const intersects = decision => decision.ordinals.some(n => ordinals.has(n));
  const blocked = blockers.filter(intersects);
  const verified = unchanged.filter(intersects);
  const changed = corrections.filter(intersects).filter(d => {
    const original = window.text.find(r => r.ordinal === d.ordinals[0]);
    const archivedVisibleRecord = d.merge?.saved && d.ordinals.slice(1).some(n => ordinals.has(n));
    return d.saved && (archivedVisibleRecord || (original ? original.text !== d.after : d.ordinals.some(n => ordinals.has(n))));
  });
  const evidence = [
    ...blocked.map(d => ({kind: 'source-unreadable', ...d})),
    ...verified.map(d => ({kind: 'verified-punctuation', ...d})),
    ...changed.map(d => ({kind: 'saved-correction', ordinals: d.ordinals,
      pages: d.pages, evidence: d.evidence, remainingUncertainty: d.remainingUncertainty})),
  ];
  for (const item of evidence) assert(item.evidence?.trim(), 'Review needs source evidence');
  const status = blocked.length ? 'source-unreadable'
    : changed.some(d => d.remainingUncertainty) ? 'partially-corrected'
    : changed.length ? 'corrected-flagged-context'
    : verified.length ? 'verified-punctuation' : 'requires-review';
  return {...window, status, evidence};
}
