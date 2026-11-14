'use strict';
// Card DB: one note is one database, shown as cards side by side (or as a table).
// The note stays ordinary Markdown that Obsidian reads as is:
//
//   ## 山田さん ^c1          ← one card per heading (the shallowest heading level in the note)
//   - 会社: ○○商事           ← its fields: "- key: value" lines right under the heading
//   - 電話: 03-…
//
//   自由なメモ…              ← the rest of the card: free text, pictures (![[Memo/_files/…]])
//
// Text before the first card stays as it is (a description of the DB).
(function (root) {
  var H = /^(#{1,6})\s+(.*?)\s*#*\s*$/, FIELD = /^\s*[-*+]\s+([^:：\[\]\n]{1,40}?)\s*[:：]\s?(.*)$/, BID = /\s+\^([A-Za-z0-9-]+)$/;
  var IMG = /!\[\[(?:Memo\/_files\/)?([A-Za-z0-9_.-]+\.(?:jpe?g|png|gif|webp|bmp|heic|heif))(?:\|[^\]]*)?\]\]/i;

  function parse(body) {
    var lines = String(body || '').replace(/\r\n/g, '\n').split('\n');
    var level = 0, fence = null;
    lines.forEach(function (l) {
      if (fence) { if (l.trim().indexOf(fence) === 0) fence = null; return; }
      var f = /^\s*(```|~~~)/.exec(l);
      if (f) { fence = f[1]; return; }
      var m = H.exec(l);
      if (m && (!level || m[1].length < level)) level = m[1].length;
    });
    var db = { level: level || 2, pre: [], cards: [] }, card = null, inFields = false;
    fence = null;
    lines.forEach(function (l) {
      var m = !fence && H.exec(l);
      var f = /^\s*(```|~~~)/.exec(l);
      if (fence) { if (l.trim().indexOf(fence) === 0) fence = null; }
      else if (f) fence = f[1];
      if (m && m[1].length === level && !f) {
        card = { title: m[2], bid: '', fields: [], body: [] };
        var b = BID.exec(card.title);
        if (b) { card.bid = b[1]; card.title = card.title.slice(0, b.index); }
        db.cards.push(card);
        inFields = true;
        return;
      }
      if (!card) { db.pre.push(l); return; }
      if (inFields) {
        var fm = FIELD.exec(l);
        if (fm && !fence) { card.fields.push({ k: fm[1].trim(), v: fm[2].trim() }); return; }
        if (!l.trim() && !card.fields.length) return;
        inFields = false;
      }
      card.body.push(l);
    });
    db.pre = trim(db.pre);
    db.cards.forEach(function (c) { c.body = trim(c.body); });
    return db;
  }

  function trim(a) {
    var s = 0, e = a.length;
    while (s < e && !a[s].trim()) s++;
    while (e > s && !a[e - 1].trim()) e--;
    return a.slice(s, e);
  }

  function one(s) { return String(s == null ? '' : s).replace(/\n/g, ' ').trim(); }

  function serialize(db) {
    var out = db.pre.slice();
    var hashes = new Array(Math.min(6, db.level || 2) + 1).join('#');
    db.cards.forEach(function (c) {
      if (out.length) out.push('');
      out.push(hashes + ' ' + (one(c.title) || '（無題）') + (c.bid ? ' ^' + c.bid : ''));
      c.fields.forEach(function (f) { if (one(f.k)) out.push('- ' + one(f.k).replace(/[:：]/g, '') + ': ' + one(f.v)); });
      if (c.body.length) { out.push(''); c.body.forEach(function (l) { out.push(l); }); }
    });
    return out.join('\n');
  }

  // Every field name, in the order the cards first use them.
  function keys(db) {
    var seen = [], set = {};
    db.cards.forEach(function (c) { c.fields.forEach(function (f) { if (!set[f.k]) { set[f.k] = 1; seen.push(f.k); } }); });
    return seen;
  }

  function value(c, k) {
    if (k === '') return c.title;
    for (var i = 0; i < c.fields.length; i++) if (c.fields[i].k === k) return c.fields[i].v;
    return '';
  }

  // Sorting: numbers as numbers, dates and text as text (Japanese order), blanks last.
  function compare(a, b) {
    if (a === b) return 0;
    if (a === '') return 1;
    if (b === '') return -1;
    var na = Number(String(a).replace(/[,円¥\s]/g, '')), nb = Number(String(b).replace(/[,円¥\s]/g, ''));
    if (!isNaN(na) && !isNaN(nb)) return na - nb;
    return String(a).localeCompare(String(b), 'ja');
  }

  function cover(c) {
    for (var i = 0; i < c.fields.length; i++) { var m = IMG.exec(c.fields[i].v); if (m) return m[1]; }
    for (var j = 0; j < c.body.length; j++) { var n = IMG.exec(c.body[j]); if (n) return n[1]; }
    return '';
  }

  var api = { parse: parse, serialize: serialize, keys: keys, value: value, compare: compare, cover: cover };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; return; }

  // ---- the screen ------------------------------------------------------------------------------

  function open(opts) {
    var el = App.el, db = parse(opts.body), title = opts.title || '';
    var query = '', sortKey = null, desc = false, table = false;
    try { table = localStorage.getItem('cards.table') === '1'; } catch (e) {}

    var wrap = el('div', 'cards-view');
    var bar = el('div', 'cv-bar');
    var x = el('button', 'icon', '←');
    x.title = '閉じる';
    x.onclick = function () { close(); };
    bar.appendChild(x);
    var head = el('b', 'grow cv-title');
    head.title = 'DB の名前を変える';
    head.onclick = function () { if (!opts.readOnly) rename(); };
    bar.appendChild(head);
    var tableB = el('button', 'icon');
    tableB.onclick = function () { table = !table; try { localStorage.setItem('cards.table', table ? '1' : '0'); } catch (e) {} paint(); };
    bar.appendChild(tableB);
    var addB = el('button', 'icon', '＋');
    addB.title = 'カードを足す';
    addB.onclick = function () { editCard(null); };
    bar.appendChild(addB);
    wrap.appendChild(bar);

    var tools = el('div', 'cv-tools');
    var search = el('input', 'cv-search');
    search.type = 'search';
    search.placeholder = 'カードを検索';
    search.oninput = function () { query = search.value.trim().toLowerCase(); paint(); };
    tools.appendChild(search);
    var sort = el('select', 'cv-sort');
    sort.onchange = function () { var v = sort.value; sortKey = v === '\u0000' ? null : v.replace(/^[<>]/, ''); desc = v[0] === '>'; paint(); };
    tools.appendChild(sort);
    wrap.appendChild(tools);
    var list = el('div', 'cv-list');
    wrap.appendChild(list);
    var pre = el('div', 'cv-pre');
    document.body.appendChild(wrap);

    function save() {
      opts.onChange(title, serialize(db));
      paint();
    }

    function shown() {
      var cs = db.cards.map(function (c, i) { return { c: c, i: i }; });
      if (query) cs = cs.filter(function (o) {
        var t = (o.c.title + ' ' + o.c.fields.map(function (f) { return f.k + ' ' + f.v; }).join(' ') + ' ' + o.c.body.join(' ')).toLowerCase();
        return t.indexOf(query) >= 0;
      });
      if (sortKey !== null) cs.sort(function (a, b) { var r = compare(value(a.c, sortKey), value(b.c, sortKey)); return (desc ? -r : r) || a.i - b.i; });
      return cs;
    }

    function paintSort() {
      var ks = keys(db), cur = sortKey === null ? '\u0000' : (desc ? '>' : '<') + sortKey;
      sort.innerHTML = '';
      var opt = function (v, t) { var o = el('option', '', t); o.value = v; sort.appendChild(o); };
      opt('\u0000', '並び：書いた順');
      opt('<', '名前 ↑');
      opt('>', '名前 ↓');
      ks.forEach(function (k) { opt('<' + k, k + ' ↑'); opt('>' + k, k + ' ↓'); });
      sort.value = cur;
      if (sort.value !== cur) { sortKey = null; sort.value = '\u0000'; }
    }

    function paint() {
      head.textContent = title || '（名前のない DB）';
      tableB.textContent = table ? '▦' : '☰';
      tableB.title = table ? 'カードで見る' : '表で見る';
      addB.style.display = opts.readOnly ? 'none' : '';
      paintSort();
      list.innerHTML = '';
      list.className = 'cv-list' + (table ? ' as-table' : '');
      if (db.pre.length && !query) {
        pre.innerHTML = MD.html(db.pre.join('\n'));
        list.appendChild(pre);
      }
      var cs = shown();
      if (!db.cards.length) {
        list.appendChild(el('p', 'muted cv-empty', opts.readOnly ? 'カードがありません' : '「＋」でカードを足します。項目（例：会社・電話・状態）は自由に決められます。'));
        return;
      }
      if (!cs.length) { list.appendChild(el('p', 'muted cv-empty', '見つかりません')); return; }
      if (table) { list.appendChild(tableEl(cs)); return; }
      var grid = el('div', 'cv-grid');
      cs.forEach(function (o) { grid.appendChild(cardEl(o.c)); });
      list.appendChild(grid);
    }

    function cardEl(c) {
      var a = el('article', 'cv-card');
      a.dataset.i = db.cards.indexOf(c);
      var img = cover(c);
      if (img) { var im = el('img', 'cv-cover'); im.src = 'thumb/' + img; im.alt = ''; a.appendChild(im); }
      a.appendChild(el('h3', '', c.title || '（無題）'));
      if (c.fields.length) {
        var dl = el('dl');
        c.fields.slice(0, 8).forEach(function (f) {
          if (IMG.test(f.v)) return;
          dl.appendChild(el('dt', '', f.k));
          dl.appendChild(el('dd', '', MD.plain(f.v) || '—'));
        });
        a.appendChild(dl);
      }
      var text = MD.plain(c.body.filter(function (l) { return !IMG.test(l); }).join(' ')).trim();
      if (text) a.appendChild(el('p', 'cv-body', text.length > 120 ? text.slice(0, 120) + '…' : text));
      a.onclick = function () { editCard(c); };
      return a;
    }

    function tableEl(cs) {
      var ks = keys(db);
      var t = el('table', 'cv-table');
      var tr = el('tr');
      [''].concat(ks).forEach(function (k) {
        var th = el('th', '', (k || '名前') + (sortKey === k ? (desc ? ' ↓' : ' ↑') : ''));
        th.onclick = function () { if (sortKey === k) desc = !desc; else { sortKey = k; desc = false; } paint(); };
        tr.appendChild(th);
      });
      t.appendChild(tr);
      cs.forEach(function (o) {
        var r = el('tr');
        r.appendChild(el('td', 'name', o.c.title || '（無題）'));
        ks.forEach(function (k) { var v = value(o.c, k); r.appendChild(el('td', '', IMG.test(v) ? '🖼' : MD.plain(v))); });
        r.onclick = function () { editCard(o.c); };
        t.appendChild(r);
      });
      var w = el('div', 'cv-table-wrap');
      w.appendChild(t);
      return w;
    }

    function rename(then) {
      var body = el('div');
      var input = el('input', 'wide');
      input.value = title;
      input.placeholder = 'DB の名前（メモのタイトル）';
      body.appendChild(input);
      App.sheet('DB の名前', body, [['キャンセル'], ['決定', function () { title = input.value.trim(); save(); if (then) setTimeout(then, 0); }, 'primary']]);
      setTimeout(function () { input.focus(); }, 50);
    }

    // One card: name, fields (new cards start with the fields the others use), free text, pictures.
    function editCard(c) {
      var fresh = !c, ro = opts.readOnly;
      var work = c ? JSON.parse(JSON.stringify(c)) : { title: '', bid: '', fields: keys(db).map(function (k) { return { k: k, v: '' }; }), body: [] };
      if (fresh && !work.fields.length) work.fields.push({ k: '', v: '' });
      var body = el('div', 'cv-edit');
      var name = el('input', 'wide cv-name');
      name.value = work.title;
      name.placeholder = 'カードの名前';
      name.readOnly = ro;
      body.appendChild(name);
      var rows = el('div', 'cv-fields');
      body.appendChild(rows);
      function row(f) {
        var r = el('div', 'cv-field');
        var k = el('input', 'k');
        k.value = f.k;
        k.placeholder = '項目';
        k.readOnly = ro;
        var v = el('input', 'v');
        v.value = f.v;
        v.placeholder = '値';
        v.readOnly = ro;
        r.appendChild(k);
        r.appendChild(v);
        if (!ro) {
          var del = el('button', 'icon', '✕');
          del.title = 'この項目を消す';
          del.onclick = function () { r.remove(); };
          r.appendChild(del);
        }
        rows.appendChild(r);
        return k;
      }
      work.fields.forEach(row);
      if (!ro) {
        var addF = el('button', 'cv-addf', '＋ 項目');
        addF.onclick = function () { row({ k: '', v: '' }).focus(); };
        body.appendChild(addF);
      }
      var ta = el('textarea', 'wide cv-text');
      ta.rows = 4;
      ta.value = work.body.join('\n');
      ta.placeholder = 'メモ（自由に）';
      ta.readOnly = ro;
      body.appendChild(ta);
      var pics = el('div', 'cv-pics');
      body.appendChild(pics);
      function paintPics() {
        pics.innerHTML = '';
        ta.value.split('\n').forEach(function (l) { var m = IMG.exec(l); if (m) { var im = el('img'); im.src = 'thumb/' + m[1]; im.alt = ''; pics.appendChild(im); } });
      }
      paintPics();
      ta.oninput = paintPics;
      if (!ro && opts.pickImage) {
        var addP = el('button', 'cv-addf', '🖼 写真を入れる');
        addP.onclick = function () {
          opts.pickImage(function (files) {
            var add = (files || []).filter(function (f) { return /\.(jpe?g|png|gif|webp|bmp|heic|heif)$/i.test(f.name); }).map(function (f) { return '![[Memo/_files/' + f.name + ']]'; });
            if (add.length) { ta.value = (ta.value.trim() ? ta.value.trim() + '\n\n' : '') + add.join('\n\n'); paintPics(); }
          });
        };
        body.appendChild(addP);
      }
      function collect() {
        var out = { title: name.value.trim(), bid: work.bid, fields: [], body: trim(ta.value.replace(/\r\n/g, '\n').split('\n')) };
        rows.querySelectorAll('.cv-field').forEach(function (r) {
          var k = r.querySelector('.k').value.trim(), v = r.querySelector('.v').value.trim();
          if (k) out.fields.push({ k: k, v: v });
        });
        return out;
      }
      var buttons = ro ? [['閉じる']] : [];
      if (!ro && !fresh) buttons.push(['削除', function () {
        if (!confirm('「' + (c.title || '無題') + '」を消しますか？')) return false;
        db.cards.splice(db.cards.indexOf(c), 1);
        save();
      }, 'danger']);
      if (!ro && !fresh) buttons.push(['複製', function () {
        var copy = collect();
        copy.bid = '';
        copy.title = copy.title + '（コピー）';
        db.cards.splice(db.cards.indexOf(c) + 1, 0, copy);
        save();
      }]);
      if (!ro) buttons.push(['キャンセル']);
      if (!ro) buttons.push(['決定', function () {
        var out = collect();
        if (fresh && !out.title && !out.fields.some(function (f) { return f.v; }) && !out.body.length) return;
        if (!out.title) out.title = '（無題）';
        if (fresh) db.cards.push(out);
        else db.cards[db.cards.indexOf(c)] = out;
        save();
      }, 'primary']);
      App.sheet(fresh ? '新しいカード' : (ro ? c.title : 'カード'), body, buttons, { wide: true });
      if (fresh) setTimeout(function () { name.focus(); }, 50);
    }

    function close() {
      root.Cards.current = null;
      wrap.remove();
      if (opts.onClose) opts.onClose();
    }

    paint();
    root.Cards.current = { close: close };
    if (opts.startNew && !opts.readOnly) setTimeout(function () { if (!title) rename(function () { editCard(null); }); else editCard(null); }, 60);
  }

  api.open = open;
  api.current = null;
  root.Cards = api;
})(typeof window !== 'undefined' ? window : this);
