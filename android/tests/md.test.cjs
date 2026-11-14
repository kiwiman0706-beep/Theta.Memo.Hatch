// Node tests for the shared Markdown ⇄ blocks rules (node android/tests/md.test.cjs).
const assert = require('assert');
const MD = require('../app/src/main/assets/md.js');
let n = 0;
function t(name, f) { f(); n++; }

t('round trip of a mixed note', () => {
  const body = '# 見出し\n本文 **太字**\n\n- [ ] 牛乳\n  - [x] パン\n\n| a | b |\n| --- | ---: |\n| 1 | 2\\|3 |\n\n![[Memo/_files/abc.jpg]]\n\n![[Memo/_files/rec.m4a]]\n> 書き起こし\n> 2行目\n\n![[Memo/_files/doc.pdf|見積書.pdf]]\n\n```\n- [ ] not a task\n```';
  const b = MD.parse(body);
  assert.deepStrictEqual(b.map(x => x.t + (x.kind ? ':' + x.kind : '')), ['text', 'list', 'table', 'file:image', 'file:audio', 'file:pdf', 'text']);
  assert.strictEqual(b[1].items[1].indent, 1);
  assert.strictEqual(b[2].rows[1][1], '2\\|3');
  assert.strictEqual(b[2].align[1], 'right');
  assert.strictEqual(b[4].quote, '書き起こし\n2行目');
  assert.strictEqual(b[5].label, '見積書.pdf');
  const again = MD.serialize(b);
  assert.deepStrictEqual(MD.parse(again), b);
  assert.ok(again.includes('| a | b |\n| --- | ---: |'));
});
t('foreign embeds stay text', () => {
  assert.deepStrictEqual(MD.parse('![[Other/note.png]]').map(x => x.t), ['text']);
  assert.strictEqual(MD.parse('![[x9.png|300]]')[0].label, '');
});
t('summary and titles', () => {
  const s = MD.summary({ body: '- [ ] a\n- [x] b\n\n![[Memo/_files/q.ink.png]]', source: '' });
  assert.strictEqual(s.unchecked, 1); assert.strictEqual(s.checked, 1); assert.strictEqual(s.ink.length, 1);
  assert.strictEqual(MD.title({ title: '', body: '\n## 予定 **明日**\n本文' }), '予定 明日');
});
t('task details like Obsidian Tasks', () => {
  const b = MD.parse('- [ ] 電話する #重要 #緊急 📅 2026-11-01\n- [x] 送った ✅ 2026-10-29 #仕事');
  assert.deepStrictEqual(b[0].items[0], { text: '電話する', due: '2026-11-01', doneDate: '', imp: true, urg: true, checked: false, indent: 0 });
  assert.strictEqual(b[0].items[1].text, '送った #仕事');
  assert.strictEqual(MD.serialize(b), '- [ ] 電話する #重要 #緊急 📅 2026-11-01\n- [x] 送った #仕事 ✅ 2026-10-29');
});
t('outline tree round trip', () => {
  const body = '# 旅行\n\n## 準備\n\n- 持ち物\n  - [ ] 充電器\n  - [x] 財布\n- 予約\n\n本文の段落\n2行目\n\n![[Memo/_files/a.jpg]]\n\n## 当日\n\n1. 出発\n2. 到着';
  const tree = MD.outline(body, 'タイトル');
  assert.strictEqual(tree.children[0].text, '旅行');
  const prep = tree.children[0].children[0];
  assert.strictEqual(prep.text, '準備');
  assert.strictEqual(prep.children[0].children[0].task, true);
  assert.strictEqual(prep.children[2].kind, 'p');
  assert.strictEqual(MD.outlineBody(tree), body);
  prep.children[1].children.push({ kind: 'li', marker: '-', text: 'ホテル', children: [] });
  assert.ok(MD.outlineBody(tree).includes('- 予約\n  - ホテル\n\n本文'));
});
t('map ids and callouts', () => {
  const body = '## 目的 ^g1\n> 今期の話\n\n- 売上 ^m1\n  > 前年比\n  - 数値 ![[Memo/_files/a.jpg]]\n- 認知\n\n## 引用\n\n> ただの引用';
  const tree = MD.outline(body, 'T');
  const h = tree.children[0];
  assert.strictEqual(h.bid, 'g1');
  assert.deepStrictEqual(h.note, ['今期の話']);
  assert.strictEqual(h.children[0].bid, 'm1');
  assert.strictEqual(h.children[0].text, '売上');
  assert.deepStrictEqual(h.children[0].note, ['前年比']);
  assert.strictEqual(tree.children[1].children[0].kind, 'p', 'a quote after a blank line stays a paragraph');
  assert.strictEqual(MD.outlineBody(tree), body);
});
t('callouts after the rich editor rewrote the list', () => {
  const tree = MD.outline('-   売上 ^m1\n    \n    > 前年比\n    \n    -   数値\n-   認知 ^m2', 'T');
  assert.strictEqual(tree.children.length, 2);
  assert.deepStrictEqual(tree.children[0].note, ['前年比']);
  assert.strictEqual(tree.children[0].children[0].text, '数値');
  assert.strictEqual(MD.outlineBody(tree), '- 売上 ^m1\n  > 前年比\n  - 数値\n- 認知 ^m2');
});
t('card DB round trip', () => {
  const C = require('../app/src/main/assets/cards.js');
  const body = '取引先の一覧\n\n## 山田さん ^c1\n- 会社: ○○商事\n- 売上: 1,200\n\nよく電話する\n![[Memo/_files/y.jpg]]\n\n## 佐藤さん\n- 会社: △△\n- 売上: 300\n\n### 経歴\n- 2020年: 入社';
  const db = C.parse(body);
  assert.deepStrictEqual(db.pre, ['取引先の一覧']);
  assert.strictEqual(db.cards.length, 2);
  assert.strictEqual(db.cards[0].bid, 'c1');
  assert.deepStrictEqual(db.cards[0].fields, [{ k: '会社', v: '○○商事' }, { k: '売上', v: '1,200' }]);
  assert.strictEqual(C.cover(db.cards[0]), 'y.jpg');
  assert.ok(db.cards[1].body.join('\n').includes('### 経歴\n- 2020年: 入社'), 'deeper headings and later lists stay in the card');
  assert.deepStrictEqual(C.keys(db), ['会社', '売上']);
  assert.strictEqual(C.serialize(db), body);
  assert.ok(C.compare('300', '1,200') < 0, 'numbers sort as numbers');
  assert.strictEqual(C.serialize(C.parse('')), '');
});
t('spreadsheet round trip', () => {
  const S = require('../app/src/main/assets/sheet.js');
  const body = '予算の表\n\n| 項目 | 金額 | メモ |\n| --- | ---: | --- |\n| 機材 | 3,000 | a\\|b |\n| 交通 | 1200 |  |\n\n後ろの文';
  const t = S.parse(body);
  assert.deepStrictEqual(t.head, ['項目', '金額', 'メモ']);
  assert.deepStrictEqual(t.align, ['', 'r', '']);
  assert.strictEqual(t.rows[0][2], 'a|b');
  assert.strictEqual(S.aggregate(t.rows, 1, 'sum'), 4200);
  assert.strictEqual(S.aggregate(t.rows, 2, 'count'), 1);
  assert.strictEqual(S.num('¥1,500'), 1500);
  assert.strictEqual(S.num('45%'), 0.45);
  assert.ok(isNaN(S.num('3個')));
  assert.strictEqual(S.serialize(t), body);
  const e = S.parse('');
  assert.ok(e.fresh);
  assert.strictEqual(S.serialize(e), '| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n|  |  |  |\n|  |  |  |');
});
t('slides split and join', () => {
  const L = require('../app/src/main/assets/slides.js');
  const body = '# 表紙\n\n---\n\n## 目的\n見出しではない\n---\n\n- a\n\n![[Memo/_files/p.jpg]]\n\n---\n\n```\n---\n```';
  const s = L.split(body);
  assert.strictEqual(s.length, 3, '"---" right under text is a heading line, and inside code it is code');
  assert.strictEqual(L.join(s), body);
  const p = L.parts(s[1]);
  assert.strictEqual(p.title, '目的');
  assert.deepStrictEqual(p.images, ['p.jpg']);
  assert.ok(!p.text.includes('p.jpg'));
  assert.strictEqual(L.compose('題', 2, '本文'), '## 題\n\n本文');
  assert.deepStrictEqual(L.split(''), ['']);
});
console.log(n + ' md tests passed');
