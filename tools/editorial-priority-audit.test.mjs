import test from 'node:test';
import assert from 'node:assert/strict';
import {auditWindow} from './editorial-priority-audit.mjs';
const window = {ordinals: [1, 2], text: [{ordinal: 1, text: 'first.'}, {ordinal: 2, text: 'next'}]};
const correction = {ordinals: [1, 2], saved: true, after: 'first next', evidence: 'PDF p1 visible join'};
test('unreviewed context and old unchanged correction are not accepted', () => {
  assert.equal(auditWindow(window, {}).status, 'requires-review');
  assert.equal(auditWindow(window, {corrections: [{...correction, ordinals: [1], after: 'first.'}]}).status, 'requires-review');
  assert.equal(auditWindow(window, {corrections: [{...correction, saved: false}]}).status, 'requires-review');
});
test('source uncertainty takes precedence over a saved partial repair', () => {
  const blockers = [{ordinals: [2], evidence: 'Suffix behind gutter'}];
  assert.equal(auditWindow(window, {corrections: [correction], blockers}).status, 'source-unreadable');
  assert.equal(auditWindow(window, {corrections: [{...correction, remainingUncertainty: 'Clipped suffix'}]}).status, 'partially-corrected');
});
test('same survivor text with actually archived trailing noise is a correction', () => {
  const d = {...correction, after: 'first.', merge: {saved: true}};
  assert.equal(auditWindow(window, {corrections: [d]}).status, 'corrected-flagged-context');
  assert.equal(auditWindow(window, {corrections: [{...d, merge: {saved: false}}]}).status, 'requires-review');
});
test('correction and author punctuation dispositions retain evidence', () => {
  assert.equal(auditWindow(window, {corrections: [correction]}).status, 'corrected-flagged-context');
  assert.equal(auditWindow(window, {unchanged: [{ordinals: [1], evidence: 'Printed ellipsis'}]}).status, 'verified-punctuation');
  assert.throws(() => auditWindow(window, {unchanged: [{ordinals: [1]}]}), /evidence/);
});
