export function translationIndex(sentences) {
  const index = new Map();
  const add = (lemma, values) => {
    if (!lemma || lemma === '***') return;
    const key = lemma.toLocaleLowerCase('ru');
    const known = index.get(key) || new Set();
    for (const value of values || []) {
      if (typeof value === 'string' && value.trim() && !value.startsWith('не найдено в словаре')) known.add(value);
    }
    if (known.size) index.set(key, known);
  };
  for (const row of sentences) for (const token of row.document.tokens) {
    add(token.analyses?.[0]?.lemma, token.translations);
    for (const variant of token.analyses || []) add(variant.lemma, variant.translations);
  }
  return index;
}

export function restoreKnownTranslations(document, index) {
  let added = 0;
  for (const token of document.tokens) {
    for (const entry of [token, ...(token.analyses || [])]) {
      const lemma = entry === token ? token.analyses?.[0]?.lemma : entry.lemma;
      if (entry.translations?.length || !lemma) continue;
      const values = index.get(lemma.toLocaleLowerCase('ru'));
      if (values?.size) {
        entry.translations = [...values];
        added++;
      }
    }
  }
  return added;
}
