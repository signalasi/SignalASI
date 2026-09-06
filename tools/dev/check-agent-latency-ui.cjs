const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { render } = require('../../apps/desktop/src/renderer/agent-latency');
const root = path.resolve(__dirname, '../..');
const styles = fs.readFileSync(path.join(root, 'apps/desktop/src/renderer/styles.css'), 'utf8');
const locale = require('../../apps/desktop/src/renderer/locales/zh-CN.json');
const output = path.join(root, 'build/reports/agent-latency');
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch(process.env.BROWSER_EXECUTABLE
    ? { executablePath: process.env.BROWSER_EXECUTABLE } : { channel: 'chrome' });
  try {
    for (const width of [360, 640]) {
      const page = await browser.newPage({ viewport: { width, height: 960 } });
      const report = { metrics: { desktop_queue_ms: {
        count: 123, incomplete: 2, unsuccessful: 1, p50_ms: 0, p95_ms: 1234.5, p99_ms: 99999.9
      } }, dropped_events: 0, write_failures: 0 };
      await page.setContent(`<html lang="zh-CN"><meta charset="utf-8"><style>${styles}
        body { display: block; overflow: auto; padding: 12px; height: auto; }
        .agent-stage-latency { width: 100%; max-width: 520px; margin: auto; }
        </style><section class="agent-stage-latency">${render(report, {
          escapeHtml, t: (key) => locale[key] || key
        })}</section></html>`);
      assert.equal(await page.locator('.agent-latency-table tbody tr').count(), 14);
      assert.ok(await page.locator('tbody').innerText().then((text) => text.includes('broker')));
      assert.ok(await page.locator('thead').innerText().then((text) => text.includes('P99')));
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      const cells = await page.locator('.agent-latency-table td').evaluateAll((nodes) =>
        nodes.every((node) => node.scrollWidth <= node.clientWidth));
      assert.ok(cells, 'numeric cells must not clip');
      await page.screenshot({ path: path.join(output, `desktop-stages-${width}.png`), fullPage: true });
      await page.close();
    }
    process.stdout.write('Stage timing table: narrow/wide layout and screenshots passed (visual fixtures only).\n');
  } finally { await browser.close(); }
})().catch((error) => { process.stderr.write(String(error.stack || error)); process.exitCode = 1; });
