// Browser test of the shared UI with the platform mocked (NODE_PATH=$(npm root -g) node android/tests/ui.test.cjs).
// Serves android/app/src/main/assets, plays the Native bridge, and checks the main flows plus
// layout at phone widths. Screenshots go to $SHOTS (default: android/build/ui-shots).
const http = require('http');
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const SHOTS = process.env.SHOTS || path.join(__dirname, '..', 'build', 'ui-shots');
fs.mkdirSync(SHOTS, { recursive: true });

// A tiny PNG for media/ and thumb/ requests.
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  if (/^\/(media|thumb)\//.test(p)) {
    if (/\.(png|jpg)$/.test(p)) { res.writeHead(200, { 'Content-Type': 'image/png' }); return res.end(PNG); }
    res.writeHead(404); return res.end();
  }
  const f = path.join(ROOT, p);
  if (!f.startsWith(ROOT) || !fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  const type = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' }[path.extname(f)] || 'application/octet-stream';
  res.writeHead(200, { 'Content-Type': type + '; charset=utf-8' });
  fs.createReadStream(f).pipe(res);
});

const now = Date.now();
const errors = [];
const NOTES = [
  { id: 'n1', title: '買い物', body: '- [ ] 牛乳\n- [ ] パン\n- [x] 卵', color: 'yellow', pinned: true, archived: false, trashed: 0, labels: ['家'], created: now - 5e6, updated: now - 1000 },
  { id: 'n2', title: '', body: '![[Memo/_files/a1.jpg]]\n\n旅行の写真', color: '', pinned: false, archived: false, trashed: 0, labels: [], created: now - 4e6, updated: now - 2000 },
  { id: 'n3', title: '会議メモ', body: '## 議題\n**予算**について\n\n| 項目 | 金額 |\n| --- | ---: |\n| 機材 | 3000 |', color: 'blue', pinned: false, archived: false, trashed: 0, labels: ['仕事'], created: now - 3e6, updated: now - 3000 },
  { id: 'n4', title: '録音', body: '![[Memo/_files/r1.m4a]]\n> 明日の打ち合わせは10時から', color: 'green', pinned: false, archived: false, trashed: 0, labels: [], created: now - 2e6, updated: now - 4000 },
  { id: 'n5', title: '見積書', body: '![[Memo/_files/q1.pdf|見積書.pdf]]', color: '', pinned: false, archived: false, trashed: 0, labels: ['仕事'], created: now - 1e6, updated: now - 5000, source: 'https://example.com/x' },
  { id: 'n6', title: '昔のメモ', body: 'アーカイブ済み', color: '', pinned: false, archived: true, trashed: 0, labels: [], created: now - 9e6, updated: now - 9e6 },
  { id: 'n7', title: '消したメモ', body: 'ゴミ箱', color: '', pinned: false, archived: false, trashed: now - 1e5, labels: [], created: now - 9e6, updated: now - 9e6 }
];
const ymd = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
const TOMORROW = ymd(new Date(now + 864e5)), PAST = ymd(new Date(now - 3 * 864e5)), TODAY = ymd(new Date(now));
NOTES.push(
  { id: 'g1', title: '請求書の件', body: '経理から連絡あり', color: '', pinned: false, archived: false, trashed: 0, labels: [], created: now - 100, updated: now - 100, gtd: 'inbox' },
  { id: 'g2', title: '引っ越し', body: '新居へ', color: 'teal', pinned: false, archived: false, trashed: 0, labels: [], created: now - 200, updated: now - 200, gtd: 'project', important: true },
  { id: 'g3', title: '見積もり依頼', body: '', color: '', pinned: false, archived: false, trashed: 0, labels: [], created: now - 300, updated: now - 300, gtd: 'next', important: true, due: TOMORROW, parent: 'g2' },
  { id: 'g4', title: '家事', body: '- [ ] 洗濯 #重要\n- [ ] 掃除 📅 ' + PAST + '\n- [ ] 買い物', color: '', pinned: false, archived: false, trashed: 0, labels: [], created: now - 400, updated: now - 400 },
  { id: 'g5', title: '企画', body: '## 目的\n\n- 売上\n- 認知\n\n## 手段\n\n- SNS', color: '', pinned: false, archived: false, trashed: 0, labels: [], created: now - 500, updated: now - 500, view: 'mindmap' }
);
for (let i = 0; i < 30; i++) NOTES.push({ id: 'm' + i, title: i % 3 ? '' : 'メモ ' + i, body: 'テキスト ' + i + '\n'.repeat(i % 4) + '続き', color: ['', 'red', 'teal', 'purple'][i % 4], pinned: false, archived: false, trashed: 0, labels: [], created: now - 1e7 - i, updated: now - 1e7 - i });

const MOCK = `
  window.__calls = [];
  window.Native = { call: function (s) {
    var q = JSON.parse(s); window.__calls.push(q);
    var send = function (type, data) { setTimeout(function () { window.onNative({ type: type, data: data }); }, 0); };
    if (q.action === 'boot') {
      send('state', { version: '0.1.0', platform: 'android', layout: 'grid', syncEnabled: true, lastSync: Date.now(), transcribe: 'auto', appPackage: 'jp.yaegaki.theta.memo', appSha1: 'AA:BB' });
      send('notes', ${JSON.stringify(NOTES)});
    }
    if (q.action === 'camera') send('attached', { note: q.note, files: [{ name: 'cam1.jpg', label: '', mime: 'image/jpeg' }] });
    if (q.action === 'record') send('recording', { note: q.note, start: Date.now() });
    if (q.action === 'stopRecord') { var n = window.__calls.filter(function (c) { return c.action === 'record'; }).pop().note; send('recorded', { note: n, name: 'rec2.m4a', duration: 3000 });
      setTimeout(function () { window.onNative({ type: 'transcript', data: { note: n, name: 'rec2.m4a', text: '書き起こしのテスト' } }); }, 30); }
    if (q.action === 'saveInk') send('inkSaved', { note: q.note, req: q.req, name: 'ink1.ink.png' });
  } };
`;

(async () => {
  await new Promise(r => server.listen(0, r));
  const url = 'http://127.0.0.1:' + server.address().port + '/index.html';
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  errors.length = 0;
  let passed = 0;
  async function page(width, height, theme) {
    const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 2, hasTouch: true, colorScheme: theme || 'light' });
    const p = await ctx.newPage();
    p.on('pageerror', e => errors.push(e.message));
    p.on('console', m => { if (m.type() === 'error' && !/404|Failed to load resource/.test(m.text())) errors.push(m.text()); });
    await p.addInitScript(MOCK);
    await p.goto(url);
    await p.waitForSelector('.card');
    return p;
  }
  const saves = p => p.evaluate(() => window.__calls.filter(c => c.action === 'save').map(c => c.note));

  // Wall of notes
  const p = await page(390, 844);
  assert.strictEqual(await p.locator('.masonry').first().locator('.col').count(), 2, 'two columns on a phone');
  assert.ok(await p.locator('.sec', { hasText: '固定' }).count());
  assert.ok(!(await p.locator('.card', { hasText: '昔のメモ' }).count()), 'archived hidden');
  assert.ok(!(await p.locator('.card', { hasText: '消したメモ' }).count()), 'trash hidden');
  assert.strictEqual(await p.locator('.card[data-id="n1"] .card-item').count(), 2);
  assert.ok(await p.locator('.card[data-id="n3"] .card-table').count());
  assert.ok(await p.locator('.card[data-id="n4"] .chip-audio', { hasText: '明日の打ち合わせ' }).count());
  assert.ok(await p.locator('.card[data-id="n5"] .badge', { hasText: 'PDF' }).count());
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 0, 'no sideways scroll: ' + overflow);
  await p.screenshot({ path: path.join(SHOTS, 'wall-phone.png') });
  passed++;

  // Search with type chips
  await p.fill('#search', 'よさん');
  await p.waitForTimeout(300);
  assert.strictEqual(await p.locator('.card').count(), 0, 'kana is not kanji');
  await p.fill('#search', '予算');
  await p.waitForTimeout(300);
  assert.strictEqual(await p.locator('.card').count(), 1);
  await p.click('#search-close');
  await p.focus('#search');
  await p.click('.type-chip:has-text("音声")');
  assert.strictEqual(await p.locator('.card').count(), 1);
  await p.click('#search-close');
  passed++;

  // Editor: check a list item, add an item, rich text
  await p.click('.card[data-id="n1"]');
  await p.waitForSelector('.editor');
  await p.locator('.editor .item').first().locator('input[type=checkbox]').check();
  await p.click('.it-add');
  await p.keyboard.type('バター');
  await p.waitForTimeout(900);
  let s = (await saves(p)).pop();
  assert.ok(s.body.includes('- [x] 牛乳'), s.body);
  assert.ok(s.body.includes('- [ ] バター'), s.body);
  await p.screenshot({ path: path.join(SHOTS, 'editor-list.png') });
  await p.click('.ed-head .icon >> nth=0');
  assert.ok(!(await p.locator('.editor').count()));
  passed++;

  // New note: title, text with bold, a table, a photo, a recording with its transcript
  await p.click('#new-text');
  await p.waitForFunction(() => document.activeElement && document.activeElement.classList.contains('rt'));
  await p.keyboard.type('テスト');
  await p.click('.ed-title');
  await p.keyboard.type('新しいメモ');
  await p.click('.blk-text .rt');
  await p.keyboard.press('End');
  await p.keyboard.type(' ');
  await p.click('.fmt[title="太字"]');
  await p.keyboard.type('強調');
  await p.click('.ed-foot .icon[title="追加"]');
  await p.click('.menu button:has-text("表")');
  await p.locator('.tbl td').first().click();
  await p.keyboard.type('値');
  await p.click('.ed-foot .icon[title="追加"]');
  await p.click('.menu button:has-text("写真を撮る")');
  await p.click('.ed-foot .icon[title="録音"]');
  await p.waitForSelector('.rec-bar.on');
  await p.click('.rec-bar .stop');
  await p.waitForSelector('.quote:has-text("書き起こしのテスト")');
  await p.waitForTimeout(900);
  s = (await saves(p)).pop();
  if (s.title !== '新しいメモ') console.log('DEBUG', await p.evaluate(() => document.querySelector('.ed-title').textContent), (await saves(p)).map(x => x.title + '|' + x.body.slice(0, 30)));
  assert.strictEqual(s.title, '新しいメモ');
  assert.ok(/テスト \*\*強調\*\*/.test(s.body), s.body);
  assert.ok(s.body.includes('| 値 |'), s.body);
  assert.ok(s.body.includes('![[Memo/_files/cam1.jpg]]'), s.body);
  assert.ok(s.body.includes('![[Memo/_files/rec2.m4a]]\n> 書き起こしのテスト'), s.body);
  await p.screenshot({ path: path.join(SHOTS, 'editor-new.png') });
  await p.click('.ed-head .icon >> nth=0');
  passed++;

  // Handwriting with a pen
  await p.click('#new-ink');
  await p.waitForSelector('.ink-canvas');
  const box = await p.locator('.ink-canvas').boundingBox();
  await p.evaluate(({ x, y }) => {
    const c = document.querySelector('.ink-canvas');
    const ev = (type, dx, dy, pr) => c.dispatchEvent(new PointerEvent(type, { pointerId: 7, pointerType: 'pen', clientX: x + dx, clientY: y + dy, pressure: pr, buttons: type === 'pointerup' ? 0 : 1, bubbles: true }));
    ev('pointerdown', 20, 40, 0.3);
    for (let i = 1; i < 30; i++) ev('pointermove', 20 + i * 6, 40 + Math.sin(i / 3) * 20, 0.3 + i / 50);
    ev('pointerup', 200, 40, 0);
  }, { x: box.x, y: box.y });
  await p.screenshot({ path: path.join(SHOTS, 'ink.png') });
  await p.click('.ink-done');
  await p.waitForSelector('.blk-file.k-ink');
  const ink = await p.evaluate(() => window.__calls.filter(c => c.action === 'saveInk').pop());
  assert.ok(ink.png.startsWith('data:image/png;base64,'));
  assert.strictEqual(JSON.parse(ink.strokes).strokes.length, 1);
  await p.waitForTimeout(900);
  assert.ok((await saves(p)).pop().body.includes('![[Memo/_files/ink1.ink.png]]'));
  await p.click('.ed-head .icon >> nth=0');
  passed++;

  // Selection: long press, pin, archive section, trash section
  await p.locator('.card[data-id="n2"]').dispatchEvent('pointerdown', { clientX: 50, clientY: 300 });
  await p.waitForTimeout(600);
  await p.locator('.card[data-id="n2"]').dispatchEvent('pointerup');
  assert.ok(await p.evaluate(() => document.body.classList.contains('selecting')));
  await p.click('#sel-pin');
  const many = await p.evaluate(() => window.__calls.filter(c => c.action === 'saveMany').pop());
  assert.strictEqual(many.notes[0].pinned, true);
  await p.click('#menu');
  await p.click('.drawer-item:has-text("アーカイブ")');
  assert.ok(await p.locator('.card', { hasText: '昔のメモ' }).count());
  await p.click('#menu');
  await p.click('.drawer-item:has-text("ゴミ箱")');
  assert.ok(await p.locator('.card', { hasText: '消したメモ' }).count());
  await p.screenshot({ path: path.join(SHOTS, 'trash.png') });
  passed++;

  // Settings and the Keep import sheet open without errors
  await p.click('#menu');
  await p.click('.drawer-item:has-text("設定")');
  await p.waitForSelector('.sheet .settings');
  await p.screenshot({ path: path.join(SHOTS, 'settings.png') });
  await p.click('.sheet-buttons button:has-text("閉じる")');
  await p.click('#menu');
  await p.click('.drawer-item:has-text("Keepから")');
  await p.waitForSelector('.sheet .steps');
  await p.click('.sheet-buttons button:has-text("閉じる")');
  passed++;

  // GTD: child notes stay inside their parent; the matrix sorts actions into four quadrants
  {
    const g = await page(390, 844);
    assert.ok(!(await g.locator('.card[data-id="g3"]').count()), 'child note hidden on the wall');
    assert.ok(await g.locator('.card[data-id="g2"] .chip.kids', { hasText: '子メモ 1' }).count());
    assert.ok(await g.locator('.card[data-id="g4"] .it-marks', { hasText: '★' }).count());
    await g.click('#menu');
    await g.click('.drawer-item:has-text("マトリクス")');
    await g.waitForSelector('.matrix');
    const quad = q => g.locator('.quad.q' + q + ' .act-text');
    assert.deepStrictEqual(await quad(1).allTextContents(), ['見積もり依頼'], 'due tomorrow makes it urgent');
    assert.deepStrictEqual((await quad(2).allTextContents()).sort(), ['引っ越し', '洗濯'].sort());
    assert.deepStrictEqual(await quad(3).allTextContents(), ['掃除'], 'overdue counts as urgent');
    assert.ok(!(await g.locator('.act-text', { hasText: '買い物' }).count()), 'unmarked items of ordinary notes stay out');
    await g.waitForTimeout(350);
    await g.screenshot({ path: path.join(SHOTS, 'matrix.png') });
    await g.locator('.act', { hasText: '洗濯' }).locator('.act-move').click();
    await g.click('.menu button:has-text("今すぐやる")');
    let sv = (await saves(g)).pop();
    assert.ok(sv.body.includes('- [ ] 洗濯 #重要 #緊急'), sv.body);
    await g.waitForTimeout(400);
    await g.locator('.act', { hasText: '掃除' }).locator('input').check();
    await g.waitForTimeout(500);
    sv = (await saves(g)).pop();
    assert.ok(sv.body.includes('- [x] 掃除 📅 ' + PAST + ' ✅ ' + TODAY), sv.body);

    // Inbox: clarify asks the GTD questions
    await g.click('#menu');
    await g.click('.drawer-item:has-text("受信箱")');
    await g.click('.card[data-id="g1"]');
    await g.click('.ed-gtd .primary-chip:has-text("整理する")');
    for (const answer of ['はい', 'いいえ', 'はい', '自分', '決まっていない']) await g.click('.clarify .choice:has-text("' + answer + '")');
    await g.waitForTimeout(300);
    sv = (await saves(g)).filter(n => n.id === 'g1').pop();
    assert.strictEqual(sv.gtd, 'next');
    await g.click('.ed-head .icon >> nth=0');
    passed++;

    // Nesting: a project's child notes, the breadcrumb, and back to the parent
    await g.click('#menu');
    await g.click('.drawer-item:has-text("プロジェクト")');
    await g.click('.card[data-id="g2"]');
    await g.waitForSelector('.ed-kids .kid');
    assert.ok(await g.locator('.kids-head', { hasText: '0 / 1 完了' }).count());
    await g.click('.kid:has-text("見積もり依頼")');
    await g.waitForSelector('.ed-crumbs .crumb:has-text("引っ越し")');
    await g.screenshot({ path: path.join(SHOTS, 'child-note.png') });
    await g.click('.ed-head .icon >> nth=0');
    await g.waitForSelector('.ed-kids .kid');
    assert.strictEqual(await g.locator('.ed-title').textContent(), '引っ越し', 'closing a child returns to its parent');
    await g.click('.ed-head .icon >> nth=0');
    passed++;

    // Mind map: opens by itself for this note; adding a branch rewrites the Markdown
    await g.click('#menu');
    await g.click('.drawer-item:has-text("メモ")');
    await g.click('.card[data-id="g5"]');
    await g.waitForSelector('.mindmap .mm-node');
    assert.strictEqual(await g.locator('.mm-node').count(), 6);
    await g.screenshot({ path: path.join(SHOTS, 'mindmap.png') });
    await g.locator('.mm-node text', { hasText: '売上' }).click();
    await g.click('.mm-tool[title="子を追加"]');
    await g.fill('.sheet input', '数値目標');
    await g.click('.sheet-buttons button:has-text("決定")');
    await g.waitForTimeout(900);
    sv = (await saves(g)).filter(n => n.id === 'g5').pop();
    assert.ok(sv && sv.body.includes('- 売上\n  - 数値目標\n- 認知'), sv && sv.body);
    assert.strictEqual(await g.locator('.mm-node').count(), 7);
    await g.click('.mm-bar .icon >> nth=0');
    assert.ok(await g.locator('.editor .rt', { hasText: '数値目標' }).count(), 'editor shows the new branch');
    await g.context().close();
    passed++;
  }

  // Narrow phone, tablet and dark theme
  for (const [w, h, theme, name] of [[320, 640, 'light', 'narrow'], [1024, 768, 'dark', 'tablet-dark']]) {
    const q = await page(w, h, theme);
    const o = await q.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(o <= 0, name + ' overflow ' + o);
    await q.screenshot({ path: path.join(SHOTS, name + '.png') });
    await q.click('.card[data-id="n3"]');
    await q.waitForSelector('.editor .tbl');
    await q.screenshot({ path: path.join(SHOTS, name + '-editor.png') });
    await q.context().close();
  }
  passed++;

  assert.deepStrictEqual(errors, []);
  console.log(passed + ' UI checks passed; screenshots in ' + SHOTS);
  await browser.close();
  server.close();
})().catch(e => { console.error(e); console.error('page errors:', errors); process.exit(1); });
