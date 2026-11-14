'use strict';
// Simple spreadsheet: the first Markdown table in a note, edited as a grid. Text before and after
// the table stays as it is. Column totals (合計・平均・最大・最小・件数) are only shown, never
// written, so the note stays an ordinary table that Obsidian and any Markdown reader show.
(function (root) {
  var ROW = /^\s*\|.*\|\s*$/, SEP = /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/;

  function cells(line) {
    var s = line.trim().replace(/^\|/, '').replace(/\|$/, ''), out = [], cur = '';
    for (var i = 0; i < s.length; i++) {
      if (s[i] === '\\' && s[i + 1] === '|') { cur += '|'; i++; continue; }
      if (s[i] === '|') { out.push(cur.trim()); cur = ''; continue; }
      cur += s[i];
    }
    out.push(cur.trim());
    return out;
  }

  function parse(body) {
    var lines = String(body || '').replace(/\r\n/g, '\n').split('\n'), fence = null;
    for (var i = 0; i + 1 < lines.length; i++) {
      var l = lines[i];
      if (fence) { if (l.trim().indexOf(fence) === 0) fence = null; continue; }
      var f = /^\s*(```|~~~)/.exec(l);
      if (f) { fence = f[1]; continue; }
      if (ROW.test(l) && SEP.test(lines[i + 1])) {
        var head = cells(l), align = cells(lines[i + 1]).map(function (c) { return /^:-+:$/.test(c) ? 'c' : /-:$/.test(c) ? 'r' : ''; });
        var rows = [], j = i + 2;
        while (j < lines.length && ROW.test(lines[j])) rows.push(cells(lines[j++]));
        var w = Math.max(head.length, rows.reduce(function (m, r) { return Math.max(m, r.length); }, 0));
        return { before: lines.slice(0, i), after: lines.slice(j), head: pad(head, w), align: pad(align, w), rows: rows.map(function (r) { return pad(r, w); }) };
      }
    }
    return { before: lines.length === 1 && !lines[0] ? [] : lines, after: [], head: ['列1', '列2', '列3'], align: ['', '', ''], rows: [['', '', ''], ['', '', ''], ['', '', '']], fresh: true };
  }

  function pad(a, w) { a = a.slice(0, w); while (a.length < w) a.push(''); return a; }
  function cell(s) { return String(s == null ? '' : s).replace(/\n/g, ' ').replace(/\|/g, '\\|').trim(); }

  function serialize(t) {
    var out = t.before.slice();
    while (out.length && !out[out.length - 1].trim()) out.pop();
    if (out.length) out.push('');
    var line = function (r) { return '| ' + r.map(cell).join(' | ') + ' |'; };
    out.push(line(t.head.map(function (h, i) { return h || '列' + (i + 1); })));
    out.push('| ' + t.align.map(function (a) { return a === 'r' ? '---:' : a === 'c' ? ':---:' : '---'; }).join(' | ') + ' |');
    t.rows.forEach(function (r) { out.push(line(r)); });
    var after = t.after.slice();
    while (after.length && !after[0].trim()) after.shift();
    if (after.length) { out.push(''); after.forEach(function (l) { out.push(l); }); }
    return out.join('\n');
  }

  // "1,200" "¥300" "45%" "-2.5" → numbers; anything else → NaN.
  function num(s) {
    var v = String(s == null ? '' : s).trim().replace(/^[¥￥$]/, '').replace(/円$/, '').replace(/,/g, '');
    var pct = /%$/.test(v);
    if (pct) v = v.slice(0, -1);
    if (!/^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i.test(v)) return NaN;
    return pct ? Number(v) / 100 : Number(v);
  }

  var AGG = ['sum', 'avg', 'max', 'min', 'count'];
  var AGG_NAME = { sum: '合計', avg: '平均', max: '最大', min: '最小', count: '件数' };
  function aggregate(rows, c, kind) {
    var vals = rows.map(function (r) { return r[c]; }).filter(function (v) { return String(v).trim() !== ''; });
    if (kind === 'count') return vals.length;
    var ns = vals.map(num).filter(function (n) { return !isNaN(n); });
    if (!ns.length) return null;
    if (kind === 'sum') return ns.reduce(function (a, b) { return a + b; }, 0);
    if (kind === 'avg') return ns.reduce(function (a, b) { return a + b; }, 0) / ns.length;
    if (kind === 'max') return Math.max.apply(null, ns);
    return Math.min.apply(null, ns);
  }
  function fmt(n) {
    if (n == null) return '';
    var r = Math.round(n * 100) / 100;
    return r.toLocaleString('ja-JP', { maximumFractionDigits: 2 });
  }
  function compare(a, b) {
    if (a === b) return 0;
    if (String(a).trim() === '') return 1;
    if (String(b).trim() === '') return -1;
    var na = num(a), nb = num(b);
    if (!isNaN(na) && !isNaN(nb)) return na - nb;
    return String(a).localeCompare(String(b), 'ja');
  }

  var api = { parse: parse, serialize: serialize, num: num, aggregate: aggregate, compare: compare };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; return; }

  // ---- the screen ------------------------------------------------------------------------------

  function open(opts) {
    var el = App.el, t = parse(opts.body), title = opts.title || '', ro = !!opts.readOnly;
    var undo = [], aggs = {}, timer = null, focus = null;
    var wrap = el('div', 'sheet-view');
    var bar = el('div', 'sv-bar');
    var x = el('button', 'icon', '←');
    x.title = '閉じる';
    x.onclick = function () { close(); };
    bar.appendChild(x);
    var head = el('b', 'grow sv-title');
    head.title = '名前を変える';
    head.onclick = function () { if (!ro) rename(); };
    bar.appendChild(head);
    var undoB = el('button', 'icon', '↶');
    undoB.title = '元に戻す';
    undoB.onclick = function () { back(); };
    bar.appendChild(undoB);
    var addR = el('button', 'sv-add', '＋行');
    addR.title = '行を足す';
    addR.onclick = function () { change(function () { t.rows.push(t.head.map(function () { return ''; })); }); focusCell(t.rows.length - 1, 0); };
    var addC = el('button', 'sv-add', '＋列');
    addC.title = '列を足す';
    addC.onclick = function () { insertCol(t.head.length); };
    if (!ro) { bar.appendChild(addR); bar.appendChild(addC); }
    wrap.appendChild(bar);
    var scroller = el('div', 'sv-scroll');
    wrap.appendChild(scroller);
    var hint = el('p', 'sv-hint muted', ro ? '' : '列の見出し（A・B…）や行の番号を押すと、並べ替え・挿入・削除。下の段は押すたびに 合計→平均→最大→最小→件数。');
    wrap.appendChild(hint);
    document.body.appendChild(wrap);

    function letter(i) { var s = ''; i++; while (i > 0) { var m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); } return s; }
    function state() { return JSON.stringify({ head: t.head, align: t.align, rows: t.rows }); }

    function save(now) {
      clearTimeout(timer);
      var go = function () { opts.onChange(title, serialize(t)); };
      if (now) go(); else timer = setTimeout(go, 400);
    }
    function change(f) {
      undo.push(state());
      if (undo.length > 100) undo.shift();
      f();
      save(true);
      paint();
    }
    function back() {
      if (!undo.length) return;
      var s = JSON.parse(undo.pop());
      t.head = s.head; t.align = s.align; t.rows = s.rows;
      save(true);
      paint();
    }

    function paint() {
      head.textContent = title || '（名前のない表）';
      undoB.disabled = ro || !undo.length;
      var w = t.head.length;
      var table = el('table', 'sv-grid');
      var letters = el('tr', 'sv-letters');
      letters.appendChild(el('th', 'sv-corner'));
      for (var c = 0; c < w; c++) {
        var lt = el('th', 'sv-col', letter(c));
        lt.dataset.c = c;
        lt.onclick = colMenu;
        letters.appendChild(lt);
      }
      table.appendChild(letters);
      var hr = el('tr', 'sv-head');
      hr.appendChild(el('th', 'sv-num', ''));
      t.head.forEach(function (h, c) { hr.appendChild(cellEl(-1, c, h)); });
      table.appendChild(hr);
      t.rows.forEach(function (r, i) {
        var tr = el('tr');
        var n = el('th', 'sv-num', String(i + 1));
        n.dataset.r = i;
        n.onclick = rowMenu;
        tr.appendChild(n);
        r.forEach(function (v, c) { tr.appendChild(cellEl(i, c, v)); });
        table.appendChild(tr);
      });
      var foot = el('tr', 'sv-foot');
      foot.appendChild(el('th', 'sv-num', ''));
      for (var k = 0; k < w; k++) {
        var td = el('td', 'sv-agg' + (t.align[k] === 'r' ? ' r' : ''));
        td.dataset.c = k;
        td.onclick = function () { var c2 = +this.dataset.c; aggs[c2] = AGG[(AGG.indexOf(this.dataset.kind) + 1) % AGG.length]; paint(); };
        foot.appendChild(td);
      }
      table.appendChild(foot);
      paintFoot(table);
      var top = scroller.scrollTop, left = scroller.scrollLeft;
      scroller.innerHTML = '';
      scroller.appendChild(table);
      scroller.scrollTop = top;
      scroller.scrollLeft = left;
      if (focus) { var f = focus; focus = null; var inp = scroller.querySelector('input[data-r="' + f[0] + '"][data-c="' + f[1] + '"]'); if (inp) { inp.focus(); inp.select(); } }
    }

    function cellEl(r, c, v) {
      var td = el('td', (r < 0 ? 'h' : '') + (t.align[c] === 'r' ? ' r' : t.align[c] === 'c' ? ' c' : '') + (r >= 0 && !isNaN(num(v)) ? ' n' : ''));
      var inp = el('input');
      inp.value = v;
      inp.readOnly = ro;
      inp.dataset.r = r;
      inp.dataset.c = c;
      inp.enterKeyHint = 'next';
      var before = null;
      inp.onfocus = function () { before = state(); };
      inp.oninput = function () {
        if (r < 0) t.head[c] = inp.value; else t.rows[r][c] = inp.value;
        td.classList.toggle('n', r >= 0 && !isNaN(num(inp.value)));
        save(false);
        paintFoot();
      };
      inp.onchange = function () {
        if (before && before !== state()) { undo.push(before); before = state(); undoB.disabled = false; }
        save(true);
        paintFoot();
      };
      inp.onkeydown = function (e) {
        if (e.isComposing || e.keyCode === 229) return;
        if (e.key === 'Enter') { e.preventDefault(); inp.onchange(); if (r + 1 >= t.rows.length) { addR.onclick(); focusCell(r + 1, c); paint(); } else go(r + 1, c); }
        else if (e.key === 'Tab') { e.preventDefault(); go(r, c + (e.shiftKey ? -1 : 1)); }
        else if (e.key === 'ArrowDown' && !e.altKey) { e.preventDefault(); go(r + 1, c); }
        else if (e.key === 'ArrowUp' && !e.altKey) { e.preventDefault(); go(r - 1, c); }
      };
      td.appendChild(inp);
      return td;
    }
    // The totals row only (the cells being typed in stay as they are).
    function paintFoot(table) {
      (table || scroller).querySelectorAll('.sv-agg').forEach(function (td) {
        var k = +td.dataset.c;
        var kind = aggs[k] || (t.rows.some(function (r) { return !isNaN(num(r[k])); }) ? 'sum' : 'count');
        var v = aggregate(t.rows, k, kind);
        td.dataset.kind = kind;
        td.textContent = v == null ? '' : AGG_NAME[kind] + ' ' + fmt(v);
      });
    }
    function go(r, c) {
      if (c < 0) { r--; c = t.head.length - 1; }
      if (c >= t.head.length) { r++; c = 0; }
      var inp = scroller.querySelector('input[data-r="' + r + '"][data-c="' + c + '"]');
      if (inp) { inp.focus(); inp.select(); }
    }
    function focusCell(r, c) { focus = [r, c]; }

    function insertCol(at) {
      change(function () {
        t.head.splice(at, 0, '列' + (t.head.length + 1));
        t.align.splice(at, 0, '');
        t.rows.forEach(function (r) { r.splice(at, 0, ''); });
        aggs = {};
      });
      focusCell(-1, at);
      paint();
    }

    function colMenu(e) {
      var c = +e.currentTarget.dataset.c, items = [
        ['↑ この列で並べ替え（小さい順）', function () { sortBy(c, false); }],
        ['↓ この列で並べ替え（大きい順）', function () { sortBy(c, true); }]
      ];
      if (!ro) {
        items.push(null);
        items.push(['⇤ 左に列を入れる', function () { insertCol(c); }]);
        items.push(['⇥ 右に列を入れる', function () { insertCol(c + 1); }]);
        if (c > 0) items.push(['← 列を左へ', function () { moveCol(c, c - 1); }]);
        if (c < t.head.length - 1) items.push(['→ 列を右へ', function () { moveCol(c, c + 1); }]);
        items.push([t.align[c] === 'r' ? '左寄せにする' : '右寄せにする（数字向き）', function () { change(function () { t.align[c] = t.align[c] === 'r' ? '' : 'r'; }); }]);
        if (t.head.length > 1) items.push(['🗑 列を消す', function () { change(function () { t.head.splice(c, 1); t.align.splice(c, 1); t.rows.forEach(function (r) { r.splice(c, 1); }); aggs = {}; }); }, true]);
      }
      App.menu(e.currentTarget, items);
    }
    function moveCol(a, b) {
      change(function () {
        [t.head, t.align].concat(t.rows).forEach(function (r) { var v = r.splice(a, 1)[0]; r.splice(b, 0, v); });
        aggs = {};
      });
    }
    function sortBy(c, desc) {
      if (ro) { t.rows.sort(function (a, b) { var r = compare(a[c], b[c]); return desc ? -r : r; }); paint(); return; }
      change(function () {
        var idx = t.rows.map(function (r, i) { return { r: r, i: i }; });
        idx.sort(function (a, b) { var r = compare(a.r[c], b.r[c]); return (desc ? -r : r) || a.i - b.i; });
        t.rows = idx.map(function (o) { return o.r; });
      });
    }

    function rowMenu(e) {
      if (ro) return;
      var i = +e.currentTarget.dataset.r, blank = function () { return t.head.map(function () { return ''; }); };
      var items = [
        ['⤒ 上に行を入れる', function () { change(function () { t.rows.splice(i, 0, blank()); }); }],
        ['⤓ 下に行を入れる', function () { change(function () { t.rows.splice(i + 1, 0, blank()); }); }]
      ];
      if (i > 0) items.push(['↑ 行を上へ', function () { change(function () { var r = t.rows.splice(i, 1)[0]; t.rows.splice(i - 1, 0, r); }); }]);
      if (i < t.rows.length - 1) items.push(['↓ 行を下へ', function () { change(function () { var r = t.rows.splice(i, 1)[0]; t.rows.splice(i + 1, 0, r); }); }]);
      items.push(['⧉ 行を複製', function () { change(function () { t.rows.splice(i + 1, 0, t.rows[i].slice()); }); }]);
      items.push(['🗑 行を消す', function () { change(function () { t.rows.splice(i, 1); }); }, true]);
      App.menu(e.currentTarget, items);
    }

    function rename() {
      var body = el('div');
      var input = el('input', 'wide');
      input.value = title;
      input.placeholder = '表の名前（メモのタイトル）';
      body.appendChild(input);
      App.sheet('名前', body, [['キャンセル'], ['決定', function () { title = input.value.trim(); save(true); paint(); }, 'primary']]);
      setTimeout(function () { input.focus(); }, 50);
    }

    function close() {
      var a = document.activeElement;
      if (a && a.blur) a.blur();
      save(true);
      root.Sheet.current = null;
      wrap.remove();
      if (opts.onClose) opts.onClose();
    }

    if (t.fresh && !ro && opts.body && String(opts.body).trim()) save(true);
    paint();
    root.Sheet.current = { close: close };
    if (opts.startNew && !ro) setTimeout(function () { if (!title) rename(); else go(-1, 0); }, 60);
  }

  api.open = open;
  api.current = null;
  root.Sheet = api;
})(typeof window !== 'undefined' ? window : this);
