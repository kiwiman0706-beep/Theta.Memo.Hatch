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
console.log(n + ' md tests passed');
