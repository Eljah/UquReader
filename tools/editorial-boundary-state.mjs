export function correctionIsApplied(current, targetText, consumed) {
  if (current.merged_into) throw new Error('Survivor is archived');
  if (current.document.text !== targetText) return false;
  for (const row of consumed) {
    if (row.merged_into && row.merged_into !== current.id) {
      throw new Error('Consumed record belongs to another survivor');
    }
  }
  return consumed.every(row => row.merged_into === current.id);
}

export function applyLayoutOverrides(document, overrides = []) {
  for (const override of overrides) {
    const matches = document.tokens.filter(t => t.start === override.start && t.surface === override.surface);
    if (matches.length !== 1) throw new Error('Layout override must identify exactly one token');
    for (const key of ['role', 'sourcePage', 'pageIndex', 'footnoteId']) {
      if (Object.hasOwn(override, key)) matches[0][key] = override[key];
    }
  }
}

export function analysisSentences(text) {
  const parts = [];
  let pending = '';
  for (const {segment} of new Intl.Segmenter('ru', {granularity: 'sentence'}).segment(text)) {
    if (!/\p{L}/u.test(segment)) {
      pending += segment;
    } else {
      parts.push(pending + segment);
      pending = '';
    }
  }
  if (!parts.length) throw new Error('No sentence with letters to analyze');
  parts[parts.length - 1] += pending;
  if (parts.join('') !== text) throw new Error('Sentence segmentation changed text');
  return parts;
}
