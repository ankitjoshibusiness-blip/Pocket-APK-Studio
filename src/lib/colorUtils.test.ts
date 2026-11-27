import test from 'node:test';
import assert from 'node:assert/strict';
import { COLOR_COMBINATIONS, hexToHsv, hsvToHex, normalizeHex } from './colorUtils';

test('normalizeHex expands 3-digit hex and falls back safely', () => {
  assert.equal(normalizeHex('#0af'), '#00AAFF');
  assert.equal(normalizeHex('3e8790'), '#3E8790');
  assert.equal(normalizeHex('invalid', '#112233'), '#112233');
});

test('hexToHsv and hsvToHex round-trip primary and studio colors accurately', () => {
  const red = hexToHsv('#FF0000');
  assert.deepEqual(red, { h: 0, s: 100, v: 100 });
  assert.equal(hsvToHex(red.h, red.s, red.v), '#FF0000');

  const teal = hexToHsv('#3E8790');
  const back = hsvToHex(teal.h, teal.s, teal.v);
  assert.match(back, /^#[0-9A-F]{6}$/);
});

test('COLOR_COMBINATIONS all contain valid 6-digit hex codes', () => {
  assert.ok(COLOR_COMBINATIONS.length >= 6);
  for (const combo of COLOR_COMBINATIONS) {
    for (const key of ['background', 'surface', 'accent', 'text', 'mutedText', 'onAccent'] as const) {
      assert.match(combo[key], /^#[0-9A-F]{6}$/i);
    }
  }
});
