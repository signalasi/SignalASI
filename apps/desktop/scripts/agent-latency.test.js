const assert = require('node:assert/strict');
const test = require('node:test');
const { render } = require('../src/renderer/agent-latency');
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

test('timing table contains P50 P95 P99 with counts and no invented samples', () => {
  const html = render({ metrics: { desktop_queue_ms: { count: 10, incomplete: 2, unsuccessful: 1,
    p50_ms: 0, p95_ms: 12.5, p99_ms: 90 } } }, { escapeHtml });
  assert.match(html, /P99 ms/);
  assert.match(html, /Samples 10 \/ Incomplete 2 \/ Failed 1/);
  assert.match(html, /<td>0<\/td><td>12.5<\/td><td>90<\/td>/);
  assert.match(html, /<td>-<\/td>/);
});

test('untrusted metric data cannot create HTML and Chinese labels render', () => {
  const html = render({ metrics: { desktop_queue_ms: { count: '<img src=x>', p95_ms: '<script>' } },
    dropped_events: 2, write_failures: 1 }, { escapeHtml,
    t: (key) => require('../src/renderer/locales/zh-CN.json')[key] || key });
  assert.doesNotMatch(html, /<img|<script/);
  assert.match(html, /任务队列/);
  assert.match(html, /记录丢弃: 2/);
});

test('empty and corrupt statistics remain unavailable', () => {
  const html = render({ metrics: { desktop_queue_ms: { p50_ms: null, p95_ms: NaN, p99_ms: -1 } } }, { escapeHtml });
  assert.doesNotMatch(html, /NaN|null|undefined/);
  assert.match(html, /<td>-<\/td><td>-<\/td><td>-<\/td>/);
});

test('recovery stages render measured values and Chinese labels', () => {
  const metrics = Object.fromEntries(['lookup', 'page', 'restore', 'publish'].map((phase) => [
    `desktop_recovery_${phase}_ms`, { count: 1, incomplete: 0, unsuccessful: 1, p50_ms: 2, p95_ms: 3, p99_ms: 4 },
  ]));
  const html = render({ metrics }, { escapeHtml,
    t: (key) => require('../src/renderer/locales/zh-CN.json')[key] || key });
  for (const label of ['恢复状态查询', '恢复结果页读取', '恢复终态归档修复', '恢复响应发布调用']) {
    assert.ok(html.includes(label));
  }
  assert.equal((html.match(/<td>2<\/td><td>3<\/td><td>4<\/td>/g) || []).length, 4);
});
