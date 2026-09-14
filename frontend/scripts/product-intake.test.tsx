import { test } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { AdminProductsPage } from '../src/admin/AdminProductsPage';
import { AdminLayout } from '../src/admin/AdminLayout';
import { featureDraft, featureValues, hasKnownFeatures } from '../src/admin/productIntake';

test('feature drafts preserve explicit false separately from unknown', () => {
  const known = { weightGrams: 1200, batteryLifeHours: 12, batteryLifeScenario: 'video_playback', noiseCancelling: false };
  const draft = featureDraft(known);
  assert.equal(draft.noiseCancelling, 'no');
  assert.equal(hasKnownFeatures(draft), true);
  assert.deepEqual(featureValues(draft), known);
  const unknown = { weightGrams: null, batteryLifeHours: null, batteryLifeScenario: null, noiseCancelling: null };
  assert.deepEqual(featureValues(featureDraft(unknown)), unknown);
  assert.equal(hasKnownFeatures(featureDraft(unknown)), false);
});

test('invalid numeric edits are not silently converted to zero or null', () => {
  const draft = featureDraft({ weightGrams: null, batteryLifeHours: null, batteryLifeScenario: null, noiseCancelling: null });
  for (const invalid of ['NaN', '-1', '0', 'Infinity', 'not-a-number']) {
    assert.throws(() => featureValues({ ...draft, weightGrams: invalid }));
  }
});

test('intake starts with original-source fields and a preview action, never auto-publishes', () => {
  const html = renderToStaticMarkup(<AdminProductsPage />);
  for (const label of ['商品简介', '规格说明', '商品编码', '提取参数并预览', '结构化参数核对']) assert.ok(html.includes(label));
  assert.ok(html.includes('此步骤不会写入商品目录'));
  assert.ok(!html.includes('确认并录入商品'));
  assert.match(html, /<option selected="">缺货<\/option>/);
});

test('intake navigation stays inside the independent administrator application', () => {
  const html = renderToStaticMarkup(<MemoryRouter initialEntries={['/admin/products']}><AdminLayout user={null} theme="light"
    refreshVersion={0} onRefresh={() => {}} onToggleTheme={() => {}}><div /></AdminLayout></MemoryRouter>);
  assert.match(html, /href="\/admin\/products"/);
  assert.ok(html.includes('商品录入'));
  assert.ok(!html.includes('进入用户工作台'));
});
