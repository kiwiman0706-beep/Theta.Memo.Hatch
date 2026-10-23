'use strict';
// Theta Memo Hatch — the shared UI. A wall of notes like Google Keep (masonry of colored cards
// with pictures, lists, recordings and files), search, labels, archive, trash and settings.
// Talks to the platform through the Theta Hatch bridge: Native.call({action,…}) / onNative.
(function () {
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  var COLORS = [
    ['', 'デフォルト'], ['red', 'トマト'], ['orange', 'オレンジ'], ['yellow', 'バナナ'], ['green', 'セージ'], ['teal', 'ミント'],
    ['blue', 'ブルー'], ['darkblue', 'ストーム'], ['purple', 'ラベンダー'], ['pink', 'ピンク'], ['brown', 'クレイ'], ['gray', 'グレー']
  ];
  var TYPES = [
    ['list', '☑ リスト'], ['image', '🖼 画像'], ['ink', '✎ 手書き'], ['audio', '🎤 音声'], ['pdf', '📄 PDF'], ['file', '📎 ファイル'],
    ['link', '🔗 リンク'], ['table', '▦ 表']
  ];

  var App = (window.App = {
    notes: [],
    byId: {},
    state: {},
    view: { section: 'notes', label: '', query: '', types: [] },
    selected: {},
    ready: false
  });

  // ---- bridge -------------------------------------------------------------------------

  App.native = function (action, args) {
    var q = args || {};
    q.action = action;
    if (window.Native && window.Native.call) window.Native.call(JSON.stringify(q));
  };

  var handlers = {};
  App.on = function (type, f) { (handlers[type] = handlers[type] || []).push(f); };

  window.onNative = function (msg) {
    var t = msg.type, d = msg.data;
    if (t === 'state') { App.state = d || {}; applyTheme(); renderChrome(); if (sheetRefresh) sheetRefresh(); }
    else if (t === 'notes') { setNotes(d || []); }
    else if (t === 'toast') App.toast(d);
    else if (t === 'error') App.toast(d, true);
    else if (t === 'busy') busy(d);
    else if (t === 'syncing') { App.syncing = !!d; renderChrome(); }
    else if (t === 'open') { var n = App.byId[d]; if (n) App.openNote(n); else App.pendingOpen = d; }
    else if (t === 'new') App.newNote(d.kind, d.text);
    else if (t === 'report') App.sheet(d.title, textBlock(d.text), [['閉じる']]);
    else if (t === 'driveSetup') setupGuide(typeof d === 'string' ? d : d && d.reason);
    else if (t === 'models') modelsArrived(d);
    (handlers[t] || []).forEach(function (f) { f(d); });
  };

  function setNotes(list) {
    App.notes = list;
    App.byId = {};
    list.forEach(function (n) { App.byId[n.id] = n; });
    if (window.Editor && Editor.current) Editor.current.external(App.byId[Editor.current.note.id]);
    render();
    if (App.pendingOpen && App.byId[App.pendingOpen]) { var id = App.pendingOpen; App.pendingOpen = null; App.openNote(App.byId[id]); }
  }

  // A note changed in this UI: keep the list current and hand it to the platform.
  App.saveNote = function (n) {
    n.updated = Date.now();
    if (!App.byId[n.id]) App.notes.push(n);
    else App.notes[App.notes.indexOf(App.byId[n.id])] = n;
    App.byId[n.id] = n;
    App.native('save', { note: n });
    renderSoon();
  };

  App.update = function (ids, change) {
    var out = [];
    ids.forEach(function (id) {
      var n = App.byId[id];
      if (!n) return;
      change(n);
      n.updated = Date.now();
      out.push(n);
    });
    App.native('saveMany', { notes: out });
    render();
  };

  App.newId = function () {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 3 | 8)).toString(16);
    });
  };

  App.blank = function () {
    var now = Date.now();
    return { id: App.newId(), title: '', body: '', color: '', pinned: false, archived: false, trashed: 0, labels: [], created: now, updated: now };
  };

  // ---- little UI kit ------------------------------------------------------------------

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  App.el = el;

  function textBlock(t) {
    var p = el('div', 'sheet-text');
    p.textContent = t;
    return p;
  }

  var toastTimer;
  App.toast = function (msg, isError) {
    if (!msg) return;
    var t = $('#toast');
    t.textContent = msg;
    t.className = 'show' + (isError ? ' error' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, isError ? 6000 : 3200);
  };

  function busy(text) {
    var b = $('#busy');
    b.textContent = text || '';
    b.classList.toggle('show', !!text);
  }

  var sheetStack = [], sheetRefresh = null;
  // A modal sheet. buttons: [[label, onClick(returns false to keep open), className]].
  App.sheet = function (title, body, buttons, opts) {
    var back = el('div', 'sheet-back');
    var s = el('div', 'sheet' + (opts && opts.wide ? ' wide' : ''));
    if (title) s.appendChild(el('h2', '', title));
    if (body) s.appendChild(body);
    var close = function () {
      back.remove();
      sheetStack.splice(sheetStack.indexOf(close), 1);
      if (opts && opts.onClose) opts.onClose();
      if (!sheetStack.length) sheetRefresh = null;
    };
    if (buttons && buttons.length) {
      var row = el('div', 'sheet-buttons');
      buttons.forEach(function (b) {
        var x = el('button', b[2] || '', b[0]);
        x.onclick = function () { if (!b[1] || b[1]() !== false) close(); };
        row.appendChild(x);
      });
      s.appendChild(row);
    }
    back.appendChild(s);
    back.addEventListener('click', function (e) { if (e.target === back) close(); });
    document.body.appendChild(back);
    sheetStack.push(close);
    return close;
  };

  // A popup menu: items [[label, onClick, danger]] (null = separator).
  App.menu = function (anchor, items) {
    var back = el('div', 'menu-back');
    var m = el('div', 'menu');
    items.forEach(function (it) {
      if (!it) { m.appendChild(el('hr')); return; }
      var b = el('button', it[2] ? 'danger' : '', it[0]);
      b.onclick = function () { close(); it[1](); };
      m.appendChild(b);
    });
    back.appendChild(m);
    document.body.appendChild(back);
    var r = anchor.getBoundingClientRect();
    var w = m.offsetWidth, h = m.offsetHeight;
    var left = Math.min(window.innerWidth - w - 8, Math.max(8, r.right - w));
    var top = r.bottom + h + 8 > window.innerHeight ? Math.max(8, r.top - h) : r.bottom;
    m.style.left = left + 'px';
    m.style.top = top + 'px';
    function close() { back.remove(); sheetStack.splice(sheetStack.indexOf(close), 1); }
    back.addEventListener('click', function (e) { if (e.target === back) close(); });
    sheetStack.push(close);
    return close;
  };

  App.colorPicker = function (anchor, current, pick) {
    var box = el('div', 'palette');
    COLORS.forEach(function (c) {
      var b = el('button', 'swatch c-' + (c[0] || 'default') + (c[0] === current ? ' on' : ''));
      b.title = c[1];
      b.setAttribute('aria-label', c[1]);
      b.onclick = function () { close(); pick(c[0]); };
      box.appendChild(b);
    });
    var close = App.sheet('', box, null);
  };
  App.COLORS = COLORS;

  App.allLabels = function () {
    var s = {};
    App.notes.forEach(function (n) { (n.labels || []).forEach(function (l) { s[l] = (s[l] || 0) + (n.trashed ? 0 : 1); }); });
    return Object.keys(s).sort(function (a, b) { return a.localeCompare(b, 'ja'); });
  };

  // Label chooser for one or more notes.
  App.labelDialog = function (notes, done) {
    var body = el('div', 'labels-edit');
    var input = el('input');
    input.placeholder = 'ラベルを作成・検索';
    body.appendChild(input);
    var list = el('div', 'labels-list');
    body.appendChild(list);
    var chosen = {};
    App.allLabels().forEach(function (l) {
      var all = notes.every(function (n) { return (n.labels || []).indexOf(l) >= 0; });
      if (all) chosen[l] = true;
    });
    function draw() {
      list.innerHTML = '';
      var q = input.value.trim();
      var labels = App.allLabels();
      Object.keys(chosen).forEach(function (l) { if (labels.indexOf(l) < 0) labels.push(l); });
      labels.filter(function (l) { return !q || norm(l).indexOf(norm(q)) >= 0; }).forEach(function (l) {
        var row = el('label', 'check-row');
        var cb = el('input');
        cb.type = 'checkbox';
        cb.checked = !!chosen[l];
        cb.onchange = function () { if (cb.checked) chosen[l] = true; else delete chosen[l]; };
        row.appendChild(cb);
        row.appendChild(el('span', '', l));
        list.appendChild(row);
      });
      var clean = q.replace(/\s+/g, '_').replace(/^#/, '');
      if (clean && labels.indexOf(clean) < 0) {
        var add = el('button', 'link', '＋「' + clean + '」を作成');
        add.onclick = function () { chosen[clean] = true; input.value = ''; draw(); };
        list.appendChild(add);
      }
    }
    input.oninput = draw;
    draw();
    App.sheet('ラベル', body, [['キャンセル'], ['完了', function () { done(Object.keys(chosen)); }, 'primary']]);
  };

  // ---- text helpers -------------------------------------------------------------------

  function norm(s) {
    s = String(s || '');
    if (s.normalize) s = s.normalize('NFKC');
    return s.toLowerCase().replace(/[ァ-ヶ]/g, function (c) { return String.fromCharCode(c.charCodeAt(0) - 0x60); });
  }
  App.norm = norm;

  function when(ms) {
    var d = new Date(ms), now = new Date();
    var hm = d.getHours() + ':' + ('0' + d.getMinutes()).slice(-2);
    if (d.toDateString() === now.toDateString()) return hm;
    if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + '月' + d.getDate() + '日';
    return d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日';
  }
  App.when = when;

  function domain(url) {
    var m = /^https?:\/\/([^/?#]+)/.exec(url || '');
    return m ? m[1].replace(/^www\./, '') : '';
  }
  App.domain = domain;

  // ---- filtering ----------------------------------------------------------------------

  function visible() {
    var v = App.view, q = norm(v.query.trim());
    var words = q ? q.split(/\s+/) : [];
    return App.notes.filter(function (n) {
      if (v.section === 'trash') { if (!n.trashed) return false; }
      else if (n.trashed) return false;
      else if (v.section === 'archive') { if (!n.archived) return false; }
      else if (v.section === 'label') { if ((n.labels || []).indexOf(v.label) < 0) return false; }
      else if (!q && !v.types.length && n.archived) return false;
      if (v.types.length) {
        var s = summaryOf(n);
        for (var i = 0; i < v.types.length; i++) {
          var t = v.types[i];
          if (t === 'list' && !s.lists) return false;
          if (t === 'image' && !s.images.some(function (b) { return b.kind === 'image' || b.kind === 'video'; })) return false;
          if (t === 'ink' && !s.ink.length) return false;
          if (t === 'audio' && !s.audio.length) return false;
          if (t === 'pdf' && !s.images.some(function (b) { return b.kind === 'pdf'; })) return false;
          if (t === 'file' && !s.files.length) return false;
          if (t === 'link' && !s.links) return false;
          if (t === 'table' && !s.tables) return false;
        }
      }
      if (words.length) {
        var hay = norm((n.title || '') + '\n' + (n.body || '') + '\n' + (n.labels || []).join(' ') + '\n' + (n.source || ''));
        for (var k = 0; k < words.length; k++) if (hay.indexOf(words[k]) < 0) return false;
      }
      return true;
    }).sort(function (a, b) {
      if (v.section === 'trash') return b.trashed - a.trashed;
      return b.updated - a.updated;
    });
  }

  var summaries = {};
  function summaryOf(n) {
    var k = n.id + ':' + n.updated + ':' + (n.body || '').length;
    var c = summaries[n.id];
    if (c && c.k === k) return c.s;
    var s = MD.summary(n);
    summaries[n.id] = { k: k, s: s };
    return s;
  }

  // ---- cards --------------------------------------------------------------------------

  function src(b, thumb) {
    if (b.kind === 'pdf' || b.kind === 'video' || thumb) return 'thumb/' + b.name;
    return 'media/' + b.name;
  }

  function card(n) {
    var c = el('article', 'card c-' + (n.color || 'default'));
    c.dataset.id = n.id;
    if (App.selected[n.id]) c.classList.add('selected');
    var blocks = MD.parse(n.body);
    var pics = blocks.filter(function (b) { return b.t === 'file' && /image|ink|video|pdf/.test(b.kind); });
    if (pics.length) {
      var cover = el('div', 'cover n' + Math.min(pics.length, 3));
      pics.slice(0, 3).forEach(function (b, i) {
        var f = el('div', 'pic k-' + b.kind);
        var img = el('img');
        img.loading = 'lazy';
        img.alt = '';
        img.src = src(b, true);
        img.onerror = function () { f.classList.add('missing'); img.remove(); };
        f.appendChild(img);
        if (b.kind === 'pdf') f.appendChild(el('span', 'badge', 'PDF'));
        if (b.kind === 'video') f.appendChild(el('span', 'badge', '▶'));
        if (i === 2 && pics.length > 3) f.appendChild(el('span', 'more', '+' + (pics.length - 3)));
        cover.appendChild(f);
      });
      c.appendChild(cover);
    }
    var inner = el('div', 'card-body');
    var t = String(n.title || '').trim();
    if (t) inner.appendChild(el('h3', 'card-title', t));
    var lines = 0;
    blocks.forEach(function (b) {
      if (lines > 14) return;
      if (b.t === 'text') {
        var d = el('div', 'card-text md');
        d.innerHTML = MD.html(b.md);
        inner.appendChild(d);
        lines += b.md.split('\n').length;
      } else if (b.t === 'list') {
        var ul = el('div', 'card-list');
        var open = b.items.filter(function (x) { return !x.checked; }), done = b.items.length - open.length;
        open.slice(0, 8).forEach(function (it) {
          var row = el('div', 'card-item');
          row.style.paddingLeft = (it.indent || 0) * 14 + 'px';
          row.appendChild(el('span', 'box'));
          var tx = el('span', 'tx');
          tx.innerHTML = MD.inline(it.text);
          row.appendChild(tx);
          ul.appendChild(row);
        });
        if (open.length > 8) ul.appendChild(el('div', 'card-more', '…'));
        if (done) ul.appendChild(el('div', 'card-more', '＋ チェック済み ' + done + ' 件'));
        inner.appendChild(ul);
        lines += Math.min(open.length, 8) + 1;
      } else if (b.t === 'table') {
        var tb = el('table', 'card-table');
        b.rows.slice(0, 5).forEach(function (r, ri) {
          var tr = el('tr');
          r.slice(0, 4).forEach(function (x) { var td = el(ri ? 'td' : 'th'); td.innerHTML = MD.inline(x); tr.appendChild(td); });
          tb.appendChild(tr);
        });
        inner.appendChild(tb);
        lines += 5;
      } else if (b.t === 'file' && b.kind === 'audio') {
        var a = el('div', 'chip-audio');
        a.appendChild(el('span', 'ic', '🎤'));
        a.appendChild(el('span', 'tx', b.quote ? b.quote.split('\n')[0] : '録音'));
        inner.appendChild(a);
        lines += 2;
      } else if (b.t === 'file' && (b.kind === 'file')) {
        inner.appendChild(el('div', 'chip-file', '📎 ' + (b.label || b.name)));
        lines += 1;
      } else if (b.t === 'file' && b.kind === 'pdf' && b.label) {
        inner.appendChild(el('div', 'chip-file', '📄 ' + b.label));
      }
    });
    var chips = el('div', 'card-chips');
    if (n.source && domain(n.source)) chips.appendChild(el('span', 'chip link', domain(n.source)));
    (n.labels || []).slice(0, 4).forEach(function (l) { chips.appendChild(el('span', 'chip', l)); });
    if ((n.labels || []).length > 4) chips.appendChild(el('span', 'chip', '+' + (n.labels.length - 4)));
    if (chips.childNodes.length) inner.appendChild(chips);
    if (inner.childNodes.length) c.appendChild(inner);
    if (!t && !inner.childNodes.length && !pics.length) c.appendChild(el('div', 'card-empty', '空のメモ'));
    if (n.pinned) c.appendChild(el('span', 'pin-mark', '📌'));
    var check = el('span', 'sel-mark', '✓');
    c.appendChild(check);
    // Tap opens; long press starts selecting (as in Keep).
    var timer = null, moved = false, startX = 0, startY = 0;
    c.addEventListener('pointerdown', function (e) {
      moved = false; startX = e.clientX; startY = e.clientY;
      timer = setTimeout(function () { timer = null; moved = true; toggleSelect(n.id); }, 480);
    });
    c.addEventListener('pointermove', function (e) {
      if (Math.abs(e.clientX - startX) + Math.abs(e.clientY - startY) > 10) { moved = true; clearTimeout(timer); timer = null; }
    });
    c.addEventListener('pointerup', function () { if (timer) { clearTimeout(timer); timer = null; } });
    c.addEventListener('pointercancel', function () { clearTimeout(timer); timer = null; moved = true; });
    c.addEventListener('click', function (e) {
      if (moved) return;
      if (e.target.tagName === 'A') return;
      if (Object.keys(App.selected).length) toggleSelect(n.id);
      else App.openNote(n);
    });
    c.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    return c;
  }

  function toggleSelect(id) {
    if (App.selected[id]) delete App.selected[id];
    else App.selected[id] = true;
    if (navigator.vibrate) try { navigator.vibrate(12); } catch (e) {}
    $$('.card[data-id="' + id + '"]').forEach(function (c) { c.classList.toggle('selected', !!App.selected[id]); });
    renderChrome();
  }

  // ---- the wall -----------------------------------------------------------------------

  var renderTimer = null;
  function renderSoon() {
    clearTimeout(renderTimer);
    renderTimer = setTimeout(render, 250);
  }

  var pending = [], sentinel = null, columns = [];
  function columnCount() {
    var w = $('#wall').clientWidth;
    if ((App.state.layout || 'grid') === 'list') return 1;
    return Math.max(2, Math.min(6, Math.floor((w + 8) / 188)));
  }

  function render() {
    clearTimeout(renderTimer);
    if (!App.ready) return;
    renderChrome();
    var wall = $('#wall');
    var keepScroll = window.scrollY;
    wall.innerHTML = '';
    wall.classList.toggle('list', columnCount() === 1);
    var list = visible();
    var pinned = App.view.section === 'trash' || App.view.section === 'archive' ? [] : list.filter(function (n) { return n.pinned; });
    var others = list.filter(function (n) { return pinned.indexOf(n) < 0; });
    pending = [];
    if (!list.length) {
      var empty = el('div', 'empty');
      var msg = App.view.query || App.view.types.length ? '一致するメモはありません'
        : App.view.section === 'trash' ? 'ゴミ箱は空です（30日たったメモは完全に削除されます）'
        : App.view.section === 'archive' ? 'アーカイブしたメモはここに表示されます'
        : App.view.section === 'label' ? 'このラベルのメモはありません'
        : 'メモを追加すると、ここに表示されます';
      empty.appendChild(el('div', 'empty-icon', App.view.section === 'trash' ? '🗑' : '💡'));
      empty.appendChild(el('p', '', msg));
      if (App.view.section === 'notes' && !App.view.query && !App.notes.length) {
        var imp = el('button', 'link', 'Google Keepから取り込む');
        imp.onclick = keepImport;
        empty.appendChild(imp);
      }
      wall.appendChild(empty);
      return;
    }
    if (App.view.section === 'trash') {
      var bar = el('div', 'trash-bar');
      bar.appendChild(el('span', '', 'ゴミ箱のメモは30日後に完全に削除されます'));
      var emptyBtn = el('button', 'link', 'ゴミ箱を空にする');
      emptyBtn.onclick = function () {
        if (!confirm('ゴミ箱のメモ ' + list.length + ' 件を完全に削除しますか？')) return;
        App.native('delete', { ids: list.map(function (n) { return n.id; }) });
      };
      bar.appendChild(emptyBtn);
      wall.appendChild(bar);
    }
    if (pinned.length) section('固定', pinned);
    if (others.length) section(pinned.length ? 'その他' : '', others);
    pump();
    window.scrollTo(0, keepScroll);
  }

  function section(label, notes) {
    var wall = $('#wall');
    if (label) wall.appendChild(el('h4', 'sec', label));
    var grid = el('div', 'masonry');
    var n = columnCount();
    var cols = [];
    for (var i = 0; i < n; i++) { var col = el('div', 'col'); grid.appendChild(col); cols.push(col); }
    wall.appendChild(grid);
    notes.forEach(function (note) { pending.push({ note: note, cols: cols }); });
  }

  // Cards go in batches, each into the shortest column (Keep's order: left to right, top down).
  function pump() {
    var batch = pending.splice(0, 60);
    batch.forEach(function (p) {
      var shortest = p.cols[0];
      p.cols.forEach(function (c) { if (c.offsetHeight < shortest.offsetHeight - 1) shortest = c; });
      shortest.appendChild(card(p.note));
    });
    if (sentinel) { sentinel.disconnect(); sentinel = null; }
    if (pending.length && 'IntersectionObserver' in window) {
      var mark = el('div', 'sentinel');
      $('#wall').appendChild(mark);
      sentinel = new IntersectionObserver(function (es) {
        if (es[0].isIntersecting) { mark.remove(); pump(); }
      }, { rootMargin: '1200px' });
      sentinel.observe(mark);
    } else if (pending.length) setTimeout(pump, 30);
  }

  var lastWidth = 0;
  window.addEventListener('resize', function () {
    var w = window.innerWidth;
    if (Math.abs(w - lastWidth) > 40) { lastWidth = w; renderSoon(); }
  });

  // ---- chrome: top bar, chips, drawer, selection ----------------------------------------

  function renderChrome() {
    var sel = Object.keys(App.selected).filter(function (id) { return App.byId[id]; });
    document.body.classList.toggle('selecting', sel.length > 0);
    $('#sel-count').textContent = sel.length + ' 件を選択';
    var v = App.view;
    var title = v.section === 'archive' ? 'アーカイブ' : v.section === 'trash' ? 'ゴミ箱' : v.section === 'label' ? v.label : '';
    $('#section-title').textContent = title;
    document.body.classList.toggle('in-section', !!title);
    document.body.classList.toggle('searching', !!(v.query || v.types.length || App.searchOpen));
    $('#layout').textContent = (App.state.layout || 'grid') === 'list' ? '▦' : '▤';
    $('#layout').title = (App.state.layout || 'grid') === 'list' ? 'グリッド表示' : 'リスト表示';
    var s = $('#sync');
    s.classList.toggle('spin', !!App.syncing);
    s.classList.toggle('err', !!App.state.syncError);
    s.classList.toggle('off', !App.state.syncEnabled);
    s.title = !App.state.syncEnabled ? '同期していません' : App.state.syncError ? '同期の問題：' + App.state.syncError : '同期済み' + (App.state.lastSync ? '（' + when(App.state.lastSync) + '）' : '');
    var chips = $('#types');
    chips.innerHTML = '';
    TYPES.forEach(function (t) {
      var b = el('button', 'type-chip' + (v.types.indexOf(t[0]) >= 0 ? ' on' : ''), t[1]);
      b.onclick = function () {
        var i = v.types.indexOf(t[0]);
        if (i >= 0) v.types.splice(i, 1); else v.types.push(t[0]);
        render();
      };
      chips.appendChild(b);
    });
    var banner = $('#banner');
    banner.innerHTML = '';
    if (App.state.update) {
      var u = App.state.update;
      var b2 = el('button', 'banner', '新しい版 ' + u.version + ' があります — ' + (u.ready ? 'タップして更新' : 'タップしてダウンロード'));
      b2.onclick = function () { App.native('installUpdate'); };
      banner.appendChild(b2);
    }
  }

  function setSection(section, label) {
    App.view.section = section;
    App.view.label = label || '';
    App.selected = {};
    closeDrawer();
    window.scrollTo(0, 0);
    render();
  }

  function openDrawer() {
    var d = $('#drawer');
    d.innerHTML = '';
    var head = el('div', 'drawer-head');
    head.appendChild(el('div', 'logo'));
    head.appendChild(el('span', '', 'Theta Memo'));
    d.appendChild(head);
    function item(icon, label, on, active) {
      var b = el('button', 'drawer-item' + (active ? ' on' : ''));
      b.appendChild(el('span', 'ic', icon));
      b.appendChild(el('span', '', label));
      b.onclick = on;
      d.appendChild(b);
      return b;
    }
    var v = App.view;
    item('💡', 'メモ', function () { setSection('notes'); }, v.section === 'notes');
    var labels = App.allLabels();
    if (labels.length) {
      d.appendChild(el('div', 'drawer-sub', 'ラベル'));
      labels.forEach(function (l) { item('🏷', l, function () { setSection('label', l); }, v.section === 'label' && v.label === l); });
    }
    item('✎', 'ラベルの編集', function () { closeDrawer(); editLabels(); });
    d.appendChild(el('hr'));
    item('📦', 'アーカイブ', function () { setSection('archive'); }, v.section === 'archive');
    item('🗑', 'ゴミ箱', function () { setSection('trash'); }, v.section === 'trash');
    d.appendChild(el('hr'));
    item('⟳', App.state.syncEnabled ? '今すぐ同期' : 'Google Driveと同期を始める', function () { closeDrawer(); App.native('syncNow'); });
    item('🌐', 'URLからスクラップ', function () { closeDrawer(); clipDialog(); });
    item('⬇', 'Google Keepから取り込む', function () { closeDrawer(); keepImport(); });
    item('▣', 'ホーム画面にウィジェット', function () { closeDrawer(); widgetDialog(); });
    item('⚙', '設定', function () { closeDrawer(); settings(); });
    d.appendChild(el('div', 'drawer-foot', 'Theta Memo Hatch ' + (App.state.version || '')));
    document.body.classList.add('drawer-open');
  }
  function closeDrawer() { document.body.classList.remove('drawer-open'); }
  App.closeDrawer = closeDrawer;

  function editLabels() {
    var body = el('div', 'labels-edit');
    var list = el('div', 'labels-list');
    body.appendChild(list);
    function draw() {
      list.innerHTML = '';
      App.allLabels().forEach(function (l) {
        var row = el('div', 'label-row');
        var input = el('input');
        input.value = l;
        var count = App.notes.filter(function (n) { return (n.labels || []).indexOf(l) >= 0; });
        row.appendChild(input);
        var del = el('button', 'icon', '🗑');
        del.title = '削除';
        del.onclick = function () {
          if (!confirm('ラベル「' + l + '」を削除しますか？（メモは削除されません）')) return;
          App.update(count.map(function (n) { return n.id; }), function (n) { n.labels = n.labels.filter(function (x) { return x !== l; }); });
          draw();
        };
        input.onchange = function () {
          var to = input.value.trim().replace(/\s+/g, '_');
          if (!to || to === l) return;
          App.update(count.map(function (n) { return n.id; }), function (n) {
            n.labels = n.labels.map(function (x) { return x === l ? to : x; }).filter(function (x, i, a) { return a.indexOf(x) === i; });
          });
          draw();
        };
        row.appendChild(el('span', 'count', count.length + ''));
        row.appendChild(del);
        list.appendChild(row);
      });
      if (!list.childNodes.length) list.appendChild(el('p', 'muted', 'ラベルはまだありません。メモの「⋮」→「ラベル」で付けられます。'));
    }
    draw();
    App.sheet('ラベルの編集', body, [['閉じる']]);
  }

  // ---- making notes ---------------------------------------------------------------------

  App.openNote = function (n, opts) {
    App.native('editing', { id: n.id });
    Editor.open(n, opts || {});
  };

  App.newNote = function (kind, text) {
    var n = App.blank();
    if (App.view.section === 'label') n.labels = [App.view.label];
    if (kind === 'list') n.body = '- [ ] ';
    if (text) n.body = text;
    App.openNote(n, { isNew: true, kind: kind || 'text' });
  };

  function clipDialog() {
    var body = el('div');
    var input = el('input', 'wide');
    input.placeholder = 'https://…';
    input.type = 'url';
    body.appendChild(el('p', 'muted', 'ページのタイトル・画像・概要とリンクをメモにします。ブラウザの「共有」→「Theta Memoに保存」でも同じことができます。'));
    body.appendChild(input);
    App.sheet('URLからスクラップ', body, [['キャンセル'], ['保存', function () {
      if (!input.value.trim()) return false;
      App.native('clip', { url: input.value.trim() });
    }, 'primary']]);
    setTimeout(function () { input.focus(); }, 50);
  }

  function keepImport() {
    var body = el('div');
    body.innerHTML =
      '<ol class="steps"><li>PCかスマホのブラウザで <b>takeout.google.com</b> を開く</li>' +
      '<li>「選択をすべて解除」→ <b>Keep</b> だけにチェック →「次のステップ」</li>' +
      '<li>形式は <b>.zip</b>、ファイルサイズはそのまま →「エクスポートを作成」</li>' +
      '<li>届いたメールからZIPをダウンロード（いくつかに分かれていたら全部）</li>' +
      '<li>下の「ZIPを選ぶ」で選ぶ（分かれたZIPはまとめて選べます）</li></ol>' +
      '<p class="muted">色・固定・アーカイブ・ラベル・チェックリスト・画像・手書き・録音・リンクを取り込みます。同じZIPをもう一度取り込んでも、二重にはなりません。</p>';
    var row = el('label', 'check-row');
    var cb = el('input');
    cb.type = 'checkbox';
    row.appendChild(cb);
    row.appendChild(el('span', '', 'Keepのゴミ箱にあるメモも取り込む（このアプリのゴミ箱へ）'));
    body.appendChild(row);
    var open = el('button', 'link', 'Google Takeoutを開く');
    open.onclick = function () { App.native('openUrl', { url: 'https://takeout.google.com/settings/takeout/custom/keep' }); };
    body.appendChild(open);
    App.sheet('Google Keepから取り込む', body, [['閉じる'], ['ZIPを選ぶ', function () { App.native('importKeep', { trashed: cb.checked }); }, 'primary']]);
  }

  function widgetDialog() {
    var body = el('div');
    body.appendChild(el('p', 'muted', 'ホーム画面に置けます。置いたあと、長押しで大きさを変えられます。'));
    var a = el('button', 'choice');
    a.innerHTML = '<b>メモ一覧</b><span>固定したメモと最近のメモ。上のボタンでテキスト・リスト・手書き・音声・写真のメモをすぐ作れます</span>';
    a.onclick = function () { App.native('pinWidget', { kind: 'list' }); };
    var b = el('button', 'choice');
    b.innerHTML = '<b>1つのメモ</b><span>選んだメモをそのまま表示。リストはホーム画面でチェックできます</span>';
    b.onclick = function () { App.native('pinWidget', { kind: 'note' }); };
    body.appendChild(a);
    body.appendChild(b);
    App.sheet('ウィジェット', body, [['閉じる']]);
  }

  // ---- settings ----------------------------------------------------------------------------

  function field(label, input, help) {
    var f = el('label', 'field');
    f.appendChild(el('span', 'field-label', label));
    f.appendChild(input);
    if (help) f.appendChild(el('span', 'field-help', help));
    return f;
  }

  function select(options, value, on) {
    var s = el('select');
    options.forEach(function (o) {
      var op = el('option', '', o[1]);
      op.value = o[0];
      if (o[0] === value) op.selected = true;
      s.appendChild(op);
    });
    s.onchange = function () { on(s.value); };
    return s;
  }

  var modelSelect = null;
  function modelsArrived(list) {
    if (!modelSelect || !list) return;
    var cur = modelSelect.value;
    modelSelect.innerHTML = '';
    list.forEach(function (m) { var o = el('option', '', m); o.value = m; if (m === cur) o.selected = true; modelSelect.appendChild(o); });
  }

  function settings() {
    var st = App.state;
    var body = el('div', 'settings');
    body.appendChild(el('h3', '', '同期（Theta Vault）'));
    var sync = el('div', 'sync-box');
    sync.appendChild(el('p', '', st.syncEnabled
      ? 'Google Driveの「Theta Vault/Memo/」と同期しています。' + (st.lastSync ? '最終：' + when(st.lastSync) : '')
      : 'メモはこの端末だけにあります。Google Driveと同期すると、PC・Macでも（Obsidianでも）開けます。'));
    if (st.syncError) sync.appendChild(el('p', 'error-text', st.syncError));
    var row = el('div', 'button-row');
    var b1 = el('button', 'primary', st.syncEnabled ? '今すぐ同期' : '同期を始める');
    b1.onclick = function () { App.native('syncNow'); };
    row.appendChild(b1);
    var b2 = el('button', '', '初期設定ガイド');
    b2.onclick = function () { setupGuide(''); };
    row.appendChild(b2);
    if (st.syncEnabled) {
      var b3 = el('button', '', '同期を止める');
      b3.onclick = function () { App.native('syncOff'); };
      row.appendChild(b3);
    }
    sync.appendChild(row);
    body.appendChild(sync);

    body.appendChild(el('h3', '', '音声メモ'));
    body.appendChild(field('書き起こし', select([
      ['auto', '自動（端末の音声認識 → だめならGemini）'], ['device', '端末の音声認識だけ（無料・オフライン）'], ['gemini', 'Geminiだけ（高精度）'], ['off', '書き起こさない']
    ], st.transcribe || 'auto', function (v) { App.native('settings', { transcribe: v }); }),
      st.deviceRecognizer ? 'この端末はファイルからの音声認識に対応しています。' : 'この端末では端末の音声認識をファイルに使えません（Android 13以降が必要）。Geminiを使います。'));

    body.appendChild(el('h3', '', 'Gemini（手書き・音声の文字起こし）'));
    var key = el('input');
    key.type = 'password';
    key.placeholder = st.keySet ? '設定済み（変更するときだけ入力）' : 'APIキー';
    key.autocomplete = 'off';
    body.appendChild(field('APIキー', key, 'キーはこの端末の中で暗号化して保存し、Vault・同期には含めません。'));
    if (st.keyLenders && st.keyLenders.length) {
      st.keyLenders.forEach(function (l) {
        var b = el('button', 'link', l.label + ' のAPIキーを使う');
        b.onclick = function () { App.native('importKey', { package: l.package }); };
        body.appendChild(b);
      });
    }
    modelSelect = el('select');
    var mo = el('option', '', st.model || 'gemini-2.5-flash');
    mo.value = st.model || 'gemini-2.5-flash';
    modelSelect.appendChild(mo);
    modelSelect.onfocus = function () { if (st.keySet && modelSelect.options.length < 2) App.native('models'); };
    modelSelect.onchange = function () { App.native('settings', { model: modelSelect.value }); };
    body.appendChild(field('モデル', modelSelect));
    var saveKey = el('button', '', 'APIキーを保存');
    saveKey.onclick = function () { if (key.value.trim()) { App.native('settings', { key: key.value.trim() }); key.value = ''; App.toast('保存しました'); } };
    body.appendChild(saveKey);

    body.appendChild(el('h3', '', '表示'));
    body.appendChild(field('テーマ', select([['auto', '端末に合わせる'], ['light', 'ライト'], ['dark', 'ダーク']], st.theme || 'auto', function (v) {
      App.native('settings', { theme: v });
      App.state.theme = v;
      applyTheme();
    })));
    var so = el('label', 'check-row');
    var cb = el('input');
    cb.type = 'checkbox';
    cb.checked = !!st.shareOpen;
    cb.onchange = function () { App.native('settings', { shareOpen: cb.checked }); };
    so.appendChild(cb);
    so.appendChild(el('span', '', '「共有」で保存したあと、そのメモを開く'));
    body.appendChild(so);

    body.appendChild(el('h3', '', 'このアプリ'));
    body.appendChild(el('p', 'muted', 'Theta Memo Hatch ' + (st.version || '') + '　データの形式：Theta Vault（Obsidian形式）'));
    var upd = el('button', '', '更新を確認');
    upd.onclick = function () { App.native('installUpdate'); };
    body.appendChild(upd);
    App.sheet('設定', body, [['閉じる']], { wide: true });
  }

  function copyRow(label, value) {
    var r = el('div', 'copy-row');
    r.appendChild(el('span', 'k', label));
    var v = el('code', '', value || '（取得できません）');
    r.appendChild(v);
    var b = el('button', '', 'コピー');
    b.onclick = function () { App.native('copyText', { text: value }); };
    r.appendChild(b);
    return r;
  }

  function setupGuide(reason) {
    var st = App.state;
    var body = el('div', 'guide');
    if (reason) body.appendChild(el('p', 'error-text', reason));
    body.appendChild(el('p', '', 'Theta Meishi Hatch と同じGoogle Cloudプロジェクトを使います。2つ目のアプリなので、追加するのは「Android用のOAuthクライアント」1つだけです。'));
    var ol = el('ol', 'steps');
    ol.innerHTML = '<li>PCで Google Cloud Console の「認証情報」（Meishiで使ったプロジェクト）を開く</li>' +
      '<li>「認証情報を作成」→「OAuth クライアント ID」→ 種類「Android」</li>' +
      '<li>パッケージ名とSHA-1に、下の値を貼り付けて作成</li>' +
      '<li>数分待ってから、このアプリで「同期を始める」→ Googleアカウントを選んで「許可」（「確認されていないアプリ」と出たら「続行」）</li>';
    body.appendChild(ol);
    body.appendChild(copyRow('パッケージ名', st.appPackage));
    body.appendChild(copyRow('SHA-1', st.appSha1));
    var open = el('button', 'link', 'Google Cloud Consoleの「認証情報」を開く');
    open.onclick = function () { App.native('openUrl', { url: 'https://console.cloud.google.com/apis/credentials' }); };
    body.appendChild(open);
    body.appendChild(el('p', 'muted', 'まだGoogle Cloudのプロジェクトが無い場合は、先に「Google Drive API」を有効にし、OAuth同意画面（外部・テストユーザーに自分）を作ってください。手順は docs/DRIVE_SETUP.md にあります。'));
    App.sheet('Google連携の初期設定ガイド', body, [['閉じる'], ['同期を始める', function () { App.native('syncNow'); }, 'primary']], { wide: true });
  }

  // ---- theme ----------------------------------------------------------------------------

  function applyTheme() {
    var t = App.state.theme || 'auto';
    var dark = t === 'dark' || (t === 'auto' && window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  }
  if (window.matchMedia) try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme); } catch (e) {}

  // ---- back button & leaving --------------------------------------------------------------

  window.handleBack = function () {
    if (window.Ink && Ink.current) { Ink.current.close(); return true; }
    if (sheetStack.length) { sheetStack[sheetStack.length - 1](); return true; }
    if (document.body.classList.contains('drawer-open')) { closeDrawer(); return true; }
    if (window.Editor && Editor.current) { Editor.current.close(); return true; }
    if (Object.keys(App.selected).length) { App.selected = {}; render(); return true; }
    if (App.view.query || App.view.types.length || App.searchOpen) { closeSearch(); return true; }
    if (App.view.section !== 'notes') { setSection('notes'); return true; }
    return false;
  };

  window.flush = function () { if (window.Editor && Editor.current) Editor.current.flush(); };

  function closeSearch() {
    App.view.query = '';
    App.view.types = [];
    App.searchOpen = false;
    $('#search').value = '';
    $('#search').blur();
    render();
  }

  // ---- boot ----------------------------------------------------------------------------------

  function boot() {
    App.ready = true;
    applyTheme();
    $('#menu').onclick = openDrawer;
    $('#drawer-back').onclick = closeDrawer;
    var search = $('#search');
    var st = null;
    search.addEventListener('focus', function () { App.searchOpen = true; renderChrome(); });
    search.addEventListener('input', function () {
      clearTimeout(st);
      st = setTimeout(function () { App.view.query = search.value; render(); }, 150);
    });
    $('#search-close').onclick = closeSearch;
    $('#layout').onclick = function () {
      var l = (App.state.layout || 'grid') === 'grid' ? 'list' : 'grid';
      App.state.layout = l;
      App.native('settings', { layout: l });
      render();
    };
    $('#sync').onclick = function () {
      if (!App.state.syncEnabled) { setupGuide(''); return; }
      App.native('syncNow');
    };
    $('#new-text').onclick = function () { App.newNote('text'); };
    $('#new-list').onclick = function () { App.newNote('list'); };
    $('#new-ink').onclick = function () { App.newNote('ink'); };
    $('#new-audio').onclick = function () { App.newNote('audio'); };
    $('#new-image').onclick = function (e) {
      App.menu(e.currentTarget, [
        ['📷 写真を撮る', function () { App.newNote('photo'); }],
        ['🖼 画像・ファイルを選ぶ', function () { App.newNote('file'); }]
      ]);
    };
    $('#sel-cancel').onclick = function () { App.selected = {}; render(); };
    $('#sel-pin').onclick = function () {
      var ids = Object.keys(App.selected);
      var all = ids.every(function (id) { return App.byId[id] && App.byId[id].pinned; });
      App.update(ids, function (n) { n.pinned = !all; });
      App.selected = {};
      render();
    };
    $('#sel-color').onclick = function (e) {
      var ids = Object.keys(App.selected);
      App.colorPicker(e.currentTarget, '', function (c) { App.update(ids, function (n) { n.color = c; }); });
    };
    $('#sel-label').onclick = function () {
      var ids = Object.keys(App.selected);
      var notes = ids.map(function (id) { return App.byId[id]; }).filter(Boolean);
      App.labelDialog(notes, function (labels) { App.update(ids, function (n) { n.labels = labels; }); });
    };
    $('#sel-archive').onclick = function () {
      var ids = Object.keys(App.selected);
      var un = App.view.section === 'archive';
      App.update(ids, function (n) { n.archived = !un; if (!un) n.pinned = false; });
      App.toast(un ? 'アーカイブから戻しました' : 'アーカイブしました');
      App.selected = {};
      render();
    };
    $('#sel-more').onclick = function (e) {
      var ids = Object.keys(App.selected);
      var items = App.view.section === 'trash'
        ? [['元に戻す', function () { App.update(ids, function (n) { n.trashed = 0; }); App.selected = {}; render(); }],
           ['完全に削除', function () { if (confirm(ids.length + ' 件を完全に削除しますか？')) { App.native('delete', { ids: ids }); App.selected = {}; } }, true]]
        : [['削除', function () { App.update(ids, function (n) { n.trashed = Date.now(); n.pinned = false; }); App.toast(ids.length + ' 件をゴミ箱に移動しました'); App.selected = {}; render(); }, true],
           ['コピーを作成', function () {
             ids.forEach(function (id) { var o = App.byId[id]; if (!o) return; var c = JSON.parse(JSON.stringify(o)); c.id = App.newId(); c.created = Date.now(); delete c.importKey; App.saveNote(c); });
             App.selected = {};
             render();
           }]];
      App.menu(e.currentTarget, items);
    };
    window.addEventListener('scroll', function () { document.body.classList.toggle('scrolled', window.scrollY > 4); }, { passive: true });
    lastWidth = window.innerWidth;
    if (!window.Native) {
      var s = document.createElement('script');
      s.src = 'demo.js';
      document.head.appendChild(s);
      return;
    }
    App.native('boot');
  }

  App.render = render;
  App.setupGuide = setupGuide;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
