import test from 'node:test';
import assert from 'node:assert/strict';
import {translationIndex, restoreKnownTranslations} from './editorial-translations.mjs';
test('restore existing dictionary glosses by lemma without inventing or overwriting', () => {
  const index = translationIndex([{document:{tokens:[{translations:['forest'], analyses:[{lemma:'Forest', translations:['woods']}]}]}}]);
  const doc = {tokens:[{surface:'forests', analyses:[{lemma:'forest'}, {lemma:'other'}]},
    {translations:['keep'], analyses:[{lemma:'forest', translations:['preserved']}]}]};
  assert.equal(restoreKnownTranslations(doc,index),2);
  assert.deepEqual(doc.tokens[0].translations,['forest','woods']);
  assert.equal(doc.tokens[0].analyses[1].translations,undefined);
  assert.deepEqual(doc.tokens[1].translations,['keep']);
  assert.deepEqual(doc.tokens[1].analyses[0].translations,['preserved']);
  assert.equal(restoreKnownTranslations(doc,index),0);
});
