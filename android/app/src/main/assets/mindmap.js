'use strict';
// Mind map of a note, edited in place: the note's title in the middle, its headings and (nested)
// list items as branches, paragraphs and attachments as leaves, child notes as dashed branches.
// It is the same tree as the outline (MD.outline), so every edit rewrites the note's Markdown —
// Obsidian sees ordinary headings and indented lists:
//   - a branch's callout (吹き出し) is a quote right under it: "  > …"
//   - a picture in a branch is an embed in its text: "- 写真 ![[Memo/_files/x.jpg]]"
//   - links between branches and braces over branches are kept in the frontmatter
//     (map_links: ["m1 -> m2 | 理由"], map_groups: ["m1 .. m3 | まとめ"]), naming the branches by
//     their Obsidian block ids (" ^m1").
(function (root) {
  var SVGNS = 'http://www.w3.org/2000/svg';
  var BRANCH = ['#1a73e8', '#d93025', '#188038', '#f29900', '#a142f4', '#12b5cb', '#e8710a', '#9334e6'];
  var GAP_X = 46, GAP_Y = 14, IMG_W = 120, IMG_H = 84;
  var IMG = /\s*!\[\[(?:Memo\/_files\/)?([A-Za-z0-9_.-]+\.(?:jpe?g|png|gif|webp|bmp|heic|heif))(?:\|[^\]]*)?\]\]/gi;

  function svg(tag, attrs) {
    var e = document.createElementNS(SVGNS, tag);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    return e;
  }

  var measureCtx = null;
  function measure(text, font) {
    if (!measureCtx) measureCtx = document.createElement('canvas').getContext('2d');
    measureCtx.font = font;
    return measureCtx.measureText(text).width;
  }

  // Splits text into lines no wider than max (character by character, for Japanese).
  function wrap(text, font, max, most) {
    var lines = [], cur = '';
    Array.from(String(text)).forEach(function (ch) {
      if (measure(cur + ch, font) > max && cur) { lines.push(cur); cur = ch; }
      else cur += ch;
    });
    if (cur || !lines.length) lines.push(cur);
    return lines.slice(0, most || 6);
  }

  // The pictures in a branch's text, and the text without them.
  function images(text) {
    var out = [], m;
    IMG.lastIndex = 0;
    while ((m = IMG.exec(text || ''))) out.push(m[1]);
    return out;
  }
  function stripImages(text) { return String(text || '').replace(IMG, '').trim(); }
  function embeds(text) { var m = String(text || '').match(IMG); return m ? m.map(function (x) { return x.trim(); }).join(' ') : ''; }

  function label(node) {
    if (node.kind === 'root') return node.text || '（タイトルなし）';
    if (node.kind === 'note') return '📄 ' + (node.text || '（タイトルなし）');
    if (node.kind === 'h' || node.kind === 'li') {
      var t = MD.task(stripImages(node.text));
      var s = MD.plain(t.text).trim();
      var marks = (t.imp ? ' ★' : '') + (t.urg ? ' ⚡' : '') + (t.due ? ' 📅' + GTD.shortDate(t.due) : '');
      return (node.task ? (node.checked ? '☑ ' : '☐ ') : '') + (s || (images(node.text).length ? '' : '（空）')) + marks;
    }
    if (node.kind === 'p') { var p = MD.plain(node.lines.join(' ')).trim(); return p.length > 60 ? p.slice(0, 60) + '…' : p; }
    var first = node.lines[0] || '';
    var f = MD.fileLine(first);
    if (f) return { image: '🖼 写真', ink: '✎ 手書き', audio: '🎤 録音', video: '🎬 動画', pdf: '📄 ' + (f.label || 'PDF'), file: '📎 ' + (f.label || 'ファイル') }[f.kind];
    if (/^\s*\|/.test(first)) return '▦ 表';
    if (/^\s*(```|~~~)/.test(first)) return '‹/› コード';
    return '…';
  }

  // "m1 -> m2 | 理由" / "m1 .. m3 | まとめ"
  function parseRefs(list, sep) {
    return (Array.isArray(list) ? list : []).map(function (s) {
      var m = new RegExp('^\\s*\\^?([A-Za-z0-9-]+)\\s*' + sep + '\\s*\\^?([A-Za-z0-9-]+)\\s*(?:\\|\\s*(.*))?$').exec(String(s));
      return m ? { from: m[1], to: m[2], label: (m[3] || '').trim() } : null;
    }).filter(Boolean);
  }
  function refText(r, sep) { return r.from + ' ' + sep + ' ' + r.to + (r.label ? ' | ' + r.label : ''); }

  function open(opts) {
    var tree, links, groups, collapsed = new Set(), selected = null, selLink = null, selGroup = null, mode = null;
    var k = 1, tx = 0, ty = 0, undo = [], cur = null, editing = null;
    var el = App.el;

    function load(title, body, ls, gs) {
      tree = MD.outline(body, title);
      (opts.children || []).forEach(function (c) { tree.children.push({ kind: 'note', note: c, text: MD.title(c), children: [] }); });
      links = parseRefs(ls, '->');
      groups = parseRefs(gs, '\\.\\.');
      collapsed = new Set();
    }
    load(opts.title, opts.body, opts.links, opts.groups);

    var wrapEl = el('div', 'mindmap');
    var bar = el('div', 'mm-bar');
    var x = el('button', 'icon', '←');
    x.title = '閉じる';
    x.onclick = function () { close(); };
    bar.appendChild(x);
    var head = el('b', 'grow', 'マインドマップ');
    bar.appendChild(head);
    var undoB = el('button', 'icon', '↶');
    undoB.title = '元に戻す';
    undoB.onclick = function () { back(); };
    bar.appendChild(undoB);
    var fitB = el('button', 'icon', '⤢');
    fitB.title = '全体を表示';
    fitB.onclick = function () { fit(); };
    bar.appendChild(fitB);
    wrapEl.appendChild(bar);
    var stage = el('div', 'mm-stage');
    stage.tabIndex = 0;
    var s = svg('svg', { width: '100%', height: '100%' });
    var defs = svg('defs', {});
    var mk = svg('marker', { id: 'mm-arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse' });
    mk.appendChild(svg('path', { d: 'M0,0 L10,5 L0,10 z', class: 'mm-arrow' }));
    defs.appendChild(mk);
    s.appendChild(defs);
    var g = svg('g', {});
    s.appendChild(g);
    stage.appendChild(s);
    var hint = el('div', 'mm-hint');
    stage.appendChild(hint);
    wrapEl.appendChild(stage);
    var tools = el('div', 'mm-tools');
    wrapEl.appendChild(tools);
    document.body.appendChild(wrapEl);

    function tool(lbl, title, on) {
      var b = el('button', 'mm-tool', lbl);
      b.title = title;
      b.onclick = on;
      tools.appendChild(b);
      return b;
    }
    var tChild = tool('＋子', '子を追加', function () { add(selected, true); });
    var tSib = tool('＋隣', '同じ階層に追加', function () { add(selected, false); });
    var tEdit = tool('✎', '文字を直す', function () { if (selLink) editRef(selLink, true); else if (selGroup) editRef(selGroup, false); else edit(selected); });
    var tNote = tool('💬', '吹き出し', function () { callout(selected); });
    var tImg = tool('🖼', '写真を入れる', function () { addImage(selected); });
    var tLink = tool('↗', 'ほかの枝とつなぐ', function () { startMode('link'); });
    var tGroup = tool('｝', '枝をまとめてコメント', function () { startMode('group'); });
    var tTask = tool('☑', 'チェックボックス', function () { toggleTask(selected); });
    var tFold = tool('▸', '折りたたむ／ひらく', function () { fold(selected); });
    var tOpen = tool('📄', '子メモを開く', function () { if (selected && selected.kind === 'note') opts.openNote(selected.note); });
    var tDel = tool('🗑', '削除', function () { if (selLink) dropRef(links, selLink); else if (selGroup) dropRef(groups, selGroup); else remove(selected); });

    function editable(n) { return n && /^(root|h|li)$/.test(n.kind); }
    function branch(n) { return n && /^(h|li)$/.test(n.kind); }

    function paintTools() {
      var n = selected, ro = opts.readOnly, ref = selLink || selGroup;
      var kind = n ? n.kind : '';
      [tChild, tSib, tNote, tImg, tLink, tGroup, tTask, tFold, tOpen].forEach(function (b) { b.style.display = ref ? 'none' : ''; });
      tChild.disabled = ro || !editable(n);
      tSib.disabled = ro || !branch(n);
      tEdit.disabled = ro || !(ref || editable(n));
      tNote.disabled = ro || !branch(n);
      tImg.disabled = ro || !branch(n) || !opts.pickImage;
      tLink.disabled = ro || !branch(n);
      tGroup.disabled = ro || !branch(n);
      tTask.disabled = ro || kind !== 'li';
      tFold.disabled = !n || !n.children.length;
      tOpen.style.display = kind === 'note' && !ref ? '' : 'none';
      tDel.disabled = ro || !(ref || (n && !/^(root|note)$/.test(kind)));
      undoB.disabled = ro || !undo.length;
      hint.textContent = mode === 'link' ? 'つなぐ先の枝をタップ（何もないところでやめる）' : mode === 'group' ? 'まとめる最後の枝（同じ階層）をタップ。この枝だけなら同じ枝をもう一度' : '';
      hint.style.display = mode ? '' : 'none';
    }

    // ---- tree helpers --------------------------------------------------------------------------

    function walk(node, f, parent) { f(node, parent); node.children.forEach(function (c) { walk(c, f, node); }); }
    function parentOf(node) { var p = null; walk(tree, function (n, par) { if (n === node) p = par; }); return p; }
    function byBid(id) { var r = null; walk(tree, function (n) { if (n.bid === id) r = n; }); return r; }
    function within(node, top) { var r = false; walk(top, function (n) { if (n === node) r = true; }); return r; }
    function bid(node) {
      if (node.bid) return node.bid;
      var id;
      do id = 'm' + Math.random().toString(36).slice(2, 6); while (byBid(id));
      node.bid = id;
      return id;
    }

    function snapshot() {
      var copy = { kind: 'root', text: tree.text, children: tree.children.filter(function (c) { return c.kind !== 'note'; }) };
      // Drop links and braces whose branches are gone (or no longer side by side).
      links = links.filter(function (l) { return byBid(l.from) && byBid(l.to); });
      groups = groups.filter(function (r) { var a = byBid(r.from), b = byBid(r.to); return a && b && parentOf(a) === parentOf(b); });
      return { title: tree.text, body: MD.outlineBody(copy), links: links.map(function (l) { return refText(l, '->'); }), groups: groups.map(function (r) { return refText(r, '..'); }) };
    }

    // merge: part of the step just taken (naming a link that was just drawn): one undo for both.
    function commit(merge) {
      var next = snapshot();
      if (!merge && cur && JSON.stringify(cur) !== JSON.stringify(next)) { undo.push(cur); if (undo.length > 100) undo.shift(); }
      cur = next;
      opts.onChange(next.title, next.body, { links: next.links, groups: next.groups });
      draw();
    }

    function back() {
      if (!undo.length) return;
      stopEdit(false);
      var prev = undo.pop();
      var path = selected ? pathOf(selected) : null;
      load(prev.title, prev.body, prev.links, prev.groups);
      cur = prev;
      selLink = selGroup = null;
      selected = (path && nodeAt(path)) || tree;
      opts.onChange(prev.title, prev.body, { links: prev.links, groups: prev.groups });
      draw();
    }
    function pathOf(node) { var p = parentOf(node); return p ? pathOf(p).concat([p.children.indexOf(node)]) : []; }
    function nodeAt(path) { var n = tree; for (var i = 0; i < path.length; i++) { n = n.children[path[i]]; if (!n) return null; } return n; }

    // ---- edits ---------------------------------------------------------------------------------

    function newNodeUnder(parent) {
      if (parent.kind === 'root' || parent.kind === 'h') {
        var hs = parent.children.filter(function (c) { return c.kind === 'h'; });
        var lis = parent.children.filter(function (c) { return c.kind === 'li'; });
        if (hs.length && !lis.length) return { kind: 'h', level: hs[0].level, text: '', children: [] };
        return { kind: 'li', marker: '-', text: '', children: [] };
      }
      var items = parent.children.filter(function (c) { return c.kind === 'li'; });
      return { kind: 'li', marker: '-', task: items.length > 0 && items.every(function (c) { return c.task; }), checked: false, text: '', children: [] };
    }

    // Lists and paragraphs come before a parent's sub-headings, headings after them; notes last.
    function clampAt(parent, nu, at) {
      var firstH = -1, lastPlain = -1, firstNote = -1;
      parent.children.forEach(function (c, i) {
        if (c === nu) return;
        if (c.kind === 'h' && firstH < 0) firstH = i;
        if (c.kind !== 'h' && c.kind !== 'note') lastPlain = i;
        if (c.kind === 'note' && firstNote < 0) firstNote = i;
      });
      if (nu.kind !== 'h' && firstH >= 0 && at > firstH) at = firstH;
      if (nu.kind === 'h' && at <= lastPlain) at = lastPlain + 1;
      if (firstNote >= 0 && at > firstNote) at = firstNote;
      return Math.max(0, Math.min(at, parent.children.length));
    }

    function add(node, asChild) {
      if (!node || opts.readOnly) return;
      var parent = asChild ? node : parentOf(node);
      if (!parent || !editable(parent)) return;
      var nu = asChild ? newNodeUnder(node) : node.kind === 'h' ? { kind: 'h', level: node.level, text: '', children: [] } : { kind: 'li', marker: '-', task: !!node.task, checked: false, text: '', children: [] };
      var at = clampAt(parent, nu, asChild ? parent.children.length : parent.children.indexOf(node) + 1);
      parent.children.splice(at, 0, nu);
      collapsed.delete(parent);
      select(nu);
      draw();
      edit(nu, true);
    }

    // Typing right on the branch: Enter = next branch beside it, Tab = a branch under it,
    // Esc = stop. A new branch left empty goes away.
    function edit(node, fresh, first) {
      if (!editable(node) || opts.readOnly) return;
      stopEdit(true);
      var t = node.kind === 'root' ? null : MD.task(stripImages(node.text));
      var input = el('input', 'mm-edit');
      input.value = first != null ? first : node.kind === 'root' ? node.text : t.text;
      input.placeholder = node.kind === 'root' ? 'タイトル' : '内容';
      input.enterKeyHint = 'next';
      editing = { node: node, input: input, fresh: fresh, t: t, pics: node.kind === 'root' ? '' : embeds(node.text) };
      stage.appendChild(input);
      placeEditor();
      input.addEventListener('keydown', function (e) {
        if (e.isComposing || e.keyCode === 229) return;
        if (e.key === 'Enter' || e.key === 'Tab') {
          e.preventDefault();
          var n = editing.node, empty = !input.value.trim();
          stopEdit(true);
          if (empty || !parentOf(n) && n !== tree) return;
          if (e.key === 'Tab' && e.shiftKey) { outdent(n); return; }
          if (e.key === 'Tab' || n === tree) add(n, true); else add(n, false);
        } else if (e.key === 'Escape') { e.preventDefault(); stopEdit(!editing.fresh); stage.focus(); }
      });
      input.addEventListener('blur', function () { setTimeout(function () { if (editing && editing.input === input) stopEdit(true); }, 0); });
      setTimeout(function () { input.focus(); if (first == null) input.select(); }, 0);
      setTimeout(function () { reveal(node); placeEditor(); }, 350);
    }

    function placeEditor() {
      if (!editing) return;
      var n = editing.node, w = Math.max(140, n._w * k + 24);
      editing.input.style.left = (tx + n._x * k - w / 2) + 'px';
      editing.input.style.top = (ty + (n._y + n._imgH / 2) * k - 18) + 'px';
      editing.input.style.width = w + 'px';
    }

    function stopEdit(save) {
      if (!editing) return;
      var e = editing, v = e.input.value.trim(), node = e.node;
      editing = null;
      e.input.remove();
      if (!save || (e.fresh && !v && !e.pics)) {
        if (e.fresh) { var p = parentOf(node); if (p) { p.children.splice(p.children.indexOf(node), 1); select(p); } }
        draw();
        return;
      }
      if (node.kind === 'root') node.text = v;
      else { e.t.text = v; node.text = MD.taskText(e.t) + (e.pics ? ' ' + e.pics : ''); }
      commit();
    }

    function outdent(node) {
      var p = parentOf(node), gp = p && parentOf(p);
      if (!gp || !branch(node)) return;
      move(node, p, 'after');
    }

    function callout(node) {
      if (!branch(node) || opts.readOnly) return;
      var body = el('div');
      var ta = el('textarea', 'wide');
      ta.rows = 3;
      ta.value = (node.note || []).join('\n');
      ta.placeholder = 'この枝への吹き出し（空にすると消えます）';
      body.appendChild(ta);
      App.sheet('吹き出し', body, [['キャンセル'], ['決定', function () {
        var lines = ta.value.split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
        if (lines.length) node.note = lines; else delete node.note;
        commit();
      }, 'primary']]);
      setTimeout(function () { ta.focus(); }, 50);
    }

    function addImage(node) {
      if (!branch(node) || !opts.pickImage) return;
      opts.pickImage(function (files) {
        var pics = (files || []).filter(function (f) { return MD.kindOf(f.name) === 'image'; }).map(function (f) { return '![[Memo/_files/' + f.name + ']]'; });
        if (!pics.length) return;
        node.text = (String(node.text || '').trim() + ' ' + pics.join(' ')).trim();
        commit();
      });
    }

    function toggleTask(node) {
      if (!node || node.kind !== 'li') return;
      if (!node.task) { node.task = true; node.checked = false; }
      else node.checked = !node.checked;
      commit();
    }

    function fold(node) {
      if (!node || !node.children.length) return;
      if (collapsed.has(node)) collapsed.delete(node); else collapsed.add(node);
      draw();
    }

    function remove(node) {
      if (!node || /^(root|note)$/.test(node.kind)) return;
      var p = parentOf(node);
      p.children.splice(p.children.indexOf(node), 1);
      select(p);
      commit();
      App.toast('削除しました（↶ で戻せます）');
    }

    // Moving a branch: under another (zone "child") or before / after it. Under a list item every
    // heading in it becomes a list item; under a heading or the title a heading takes the level
    // that fits there.
    function move(node, target, zone) {
      if (!branch(node) || !target || within(target, node)) return false;
      var parent = zone === 'child' ? target : parentOf(target);
      if (!parent || !editable(parent)) return false;
      var plain = false;
      walk(node, function (n) { if (n.kind === 'p' || n.kind === 'raw') plain = true; });
      if (parent.kind === 'li' && plain) { App.toast('段落や写真を含む枝は、箇条書きの下には移せません'); return false; }
      var old = parentOf(node);
      old.children.splice(old.children.indexOf(node), 1);
      if (parent.kind === 'li') walk(node, function (n) { if (n.kind === 'h') { n.kind = 'li'; n.marker = '-'; delete n.level; } });
      else if (node.kind === 'h') {
        var sib = parent.children.filter(function (c) { return c.kind === 'h'; })[0];
        var level = sib ? sib.level : parent.kind === 'h' ? Math.min(6, parent.level + 1) : 2;
        var d = level - node.level;
        walk(node, function (n) { if (n.kind === 'h') n.level = Math.max(1, Math.min(6, n.level + d)); });
      }
      var at = zone === 'child' ? parent.children.length : parent.children.indexOf(target) + (zone === 'after' ? 1 : 0);
      parent.children.splice(clampAt(parent, node, at), 0, node);
      collapsed.delete(parent);
      select(node);
      commit();
      return true;
    }

    // ---- links and braces ----------------------------------------------------------------------

    function startMode(m) {
      if (!branch(selected)) return;
      mode = { kind: m, from: selected };
      paintTools();
    }

    function finishMode(node) {
      var m = mode;
      mode = null;
      if (!node || !branch(node)) { draw(); return; }
      if (m.kind === 'link') {
        if (node === m.from) { draw(); return; }
        var l = { from: bid(m.from), to: bid(node), label: '' };
        links.push(l);
        select(null);
        selLink = l;
        commit();
        editRef(l, true, true);
      } else {
        var p = parentOf(m.from);
        if (parentOf(node) !== p) { App.toast('同じ階層の枝を選んでください'); draw(); return; }
        var a = p.children.indexOf(m.from), b = p.children.indexOf(node);
        var r = { from: bid(p.children[Math.min(a, b)]), to: bid(p.children[Math.max(a, b)]), label: '' };
        groups.push(r);
        select(null);
        selGroup = r;
        commit();
        editRef(r, false, true);
      }
    }

    function editRef(r, isLink, fresh) {
      var body = el('div');
      var input = el('input', 'wide');
      input.value = r.label || '';
      input.placeholder = isLink ? 'つながりの説明（なくてもよい）' : 'まとめのコメント';
      body.appendChild(input);
      var save = function () { r.label = input.value.replace(/\|/g, '／').trim(); commit(fresh); };
      input.addEventListener('keydown', function (e) { if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); closeSheet(); save(); } });
      var closeSheet = App.sheet(isLink ? (fresh ? 'つないだ線' : '線の説明') : (fresh ? 'まとめ' : 'まとめのコメント'), body, [[fresh ? 'なしで' : 'キャンセル'], ['決定', save, 'primary']]);
      setTimeout(function () { input.focus(); }, 50);
    }

    function dropRef(list, r) {
      list.splice(list.indexOf(r), 1);
      selLink = selGroup = null;
      commit();
    }

    function select(n) { selected = n; selLink = null; selGroup = null; }

    // ---- layout -----------------------------------------------------------------------------

    function sizeOf(node, depth) {
      var font = depth === 0 ? '600 17px system-ui, sans-serif' : node.kind === 'p' ? 'italic 13px system-ui, sans-serif' : (node.kind === 'h' ? '600 ' : '') + '14px system-ui, sans-serif';
      var max = depth === 0 ? 240 : 200;
      node._font = font;
      var text = label(node);
      node._lines = text ? wrap(text, font, max) : [];
      node._pics = branch(node) ? images(node.text) : [];
      node._imgH = node._pics.length ? IMG_H + 8 : 0;
      var w = node._pics.length ? Math.min(2, node._pics.length) * (IMG_W + 6) - 6 : 0;
      node._lines.forEach(function (l) { w = Math.max(w, measure(l, font)); });
      node._w = Math.max(depth === 0 ? 90 : 40, w + (depth === 0 ? 32 : 22));
      node._lh = depth === 0 ? 22 : 19;
      node._h = node._lines.length * node._lh + (depth === 0 ? 22 : 14) + node._imgH - (node._lines.length ? 0 : 6);
      // The callout sits above the branch.
      node._bub = null;
      if (node.note && node.note.length) {
        var bl = [];
        node.note.forEach(function (l) { bl = bl.concat(wrap(l, '12px system-ui, sans-serif', 180, 4)); });
        var bw = 0;
        bl.forEach(function (l) { bw = Math.max(bw, measure(l, '12px system-ui, sans-serif')); });
        node._bub = { lines: bl.slice(0, 6), w: bw + 18, h: Math.min(6, bl.length) * 16 + 12 };
      }
      node._top = node._bub ? node._bub.h + 12 : 0;
      node._w = Math.max(node._w, node._bub ? Math.min(node._bub.w, 120) : 0);
      var kids = collapsed.has(node) ? [] : node.children;
      var total = 0;
      kids.forEach(function (c, i) { sizeOf(c, depth + 1); total += c._sh + (i ? GAP_Y : 0); });
      node._sh = Math.max(node._h + node._top, total);
    }

    function place(node, x, y, dir, color, depth) {
      node._x = x;
      node._y = y + node._top / 2;
      node._dir = dir;
      node._color = color;
      node._depth = depth;
      node._vis = true;
      var kids = collapsed.has(node) ? [] : node.children;
      var total = 0;
      kids.forEach(function (c, i) { total += c._sh + (i ? GAP_Y : 0); });
      var cy = y - total / 2;
      kids.forEach(function (c) {
        var cx = x + dir * (node._w / 2 + GAP_X + c._w / 2);
        place(c, cx, cy + c._sh / 2, dir, color, depth + 1);
        cy += c._sh + GAP_Y;
      });
    }

    function layout() {
      walk(tree, function (n) { n._vis = false; });
      sizeOf(tree, 0);
      tree._x = 0;
      tree._y = 0;
      tree._depth = 0;
      tree._dir = 1;
      tree._vis = true;
      var kids = collapsed.has(tree) ? [] : tree.children;
      var right = [], left = [], rh = 0, lh = 0;
      kids.forEach(function (c) { if (rh <= lh) { right.push(c); rh += c._sh + GAP_Y; } else { left.push(c); lh += c._sh + GAP_Y; } });
      [[right, 1, rh], [left, -1, lh]].forEach(function (side) {
        var cy = -(side[2] - GAP_Y) / 2;
        side[0].forEach(function (c) {
          var color = BRANCH[kids.indexOf(c) % BRANCH.length];
          place(c, side[1] * (tree._w / 2 + GAP_X + c._w / 2), cy + c._sh / 2, side[1], color, 1);
          cy += c._sh + GAP_Y;
        });
      });
    }

    // ---- drawing ----------------------------------------------------------------------------

    function draw() {
      layout();
      while (g.firstChild) g.removeChild(g.firstChild);
      var edges = svg('g', { class: 'mm-edges' }), extras = svg('g', {}), nodes = svg('g', {});
      g.appendChild(edges);
      g.appendChild(extras);
      g.appendChild(nodes);
      (function each(node) {
        var kids = collapsed.has(node) ? [] : node.children;
        kids.forEach(function (c) {
          var x1 = node._x + c._dir * node._w / 2, y1 = node._y, x2 = c._x - c._dir * c._w / 2, y2 = c._y, mx = (x1 + x2) / 2;
          edges.appendChild(svg('path', { d: 'M' + x1 + ',' + y1 + ' C' + mx + ',' + y1 + ' ' + mx + ',' + y2 + ' ' + x2 + ',' + y2,
            stroke: c._color, 'stroke-width': Math.max(1.5, 4 - c._depth), fill: 'none', 'stroke-opacity': 0.8,
            'stroke-dasharray': c.kind === 'note' ? '5 4' : '' }));
          each(c);
        });
        nodes.appendChild(nodeEl(node));
      })(tree);
      groups.forEach(function (r) { var e = braceEl(r); if (e) extras.appendChild(e); });
      links.forEach(function (l) { var e = linkEl(l); if (e) extras.appendChild(e); });
      paintTools();
      placeEditor();
    }

    function nodeEl(node) {
      var gg = svg('g', { class: 'mm-node k-' + node.kind + (node === selected ? ' sel' : '') + (node.checked ? ' checked' : '') + (mode && node === mode.from ? ' from' : ''),
        transform: 'translate(' + (node._x - node._w / 2) + ',' + (node._y - node._h / 2) + ')' });
      var root0 = node._depth === 0;
      if (node._bub) {
        var b = node._bub, bx = (node._w - b.w) / 2, by = -node._top;
        var bubble = svg('g', { class: 'mm-bubble' });
        bubble.appendChild(svg('path', { d: 'M' + (bx + 10) + ',' + by + ' h' + (b.w - 20) + ' q10,0 10,10 v' + (b.h - 20) + ' q0,10 -10,10 H' + (node._w / 2 + 7) + ' l-7,9 l-7,-9 H' + (bx + 10) + ' q-10,0 -10,-10 v' + (-(b.h - 20)) + ' q0,-10 10,-10 z' }));
        b.lines.forEach(function (l, i) {
          var t = svg('text', { x: node._w / 2, y: by + 18 + i * 16, 'text-anchor': 'middle' });
          t.textContent = l;
          bubble.appendChild(t);
        });
        gg.appendChild(bubble);
      }
      var rect = svg('rect', { width: node._w, height: node._h, rx: root0 ? 14 : 9, ry: root0 ? 14 : 9 });
      if (!root0) {
        rect.setAttribute('stroke', node._color);
        rect.setAttribute('fill', node.kind === 'p' || node.kind === 'raw' ? 'var(--surface)' : node._color);
        rect.setAttribute('fill-opacity', node.kind === 'p' || node.kind === 'raw' ? 1 : node._depth === 1 ? 0.22 : 0.1);
        if (node.kind === 'note') rect.setAttribute('stroke-dasharray', '5 4');
      }
      gg.appendChild(rect);
      node._pics.slice(0, 2).forEach(function (name, i) {
        var n2 = Math.min(2, node._pics.length), x0 = (node._w - (n2 * (IMG_W + 6) - 6)) / 2;
        gg.appendChild(svg('image', { href: 'thumb/' + name, x: x0 + i * (IMG_W + 6), y: 7, width: IMG_W, height: IMG_H, preserveAspectRatio: 'xMidYMid slice', class: 'mm-img' }));
      });
      if (node._pics.length > 2) { var more = svg('text', { x: node._w - 8, y: IMG_H, 'text-anchor': 'end', class: 'mm-more' }); more.textContent = '+' + (node._pics.length - 2); gg.appendChild(more); }
      node._lines.forEach(function (l, i) {
        var t = svg('text', { x: node._w / 2, y: (root0 ? 11 : 7) + node._imgH + node._lh * (i + 0.78), 'text-anchor': 'middle' });
        t.setAttribute('style', 'font:' + node._font);
        t.textContent = l;
        gg.appendChild(t);
      });
      if (node.children.length) {
        var cx = node._dir === -1 ? -9 : node._w + 9;
        if (root0) cx = node._w + 9;
        var dot = svg('g', { class: 'mm-fold', transform: 'translate(' + cx + ',' + node._h / 2 + ')' });
        dot.appendChild(svg('circle', { r: 8, stroke: node._color || 'var(--accent)' }));
        var tt = svg('text', { y: 4, 'text-anchor': 'middle' });
        tt.textContent = collapsed.has(node) ? String(node.children.length) : '−';
        dot.appendChild(tt);
        gg.appendChild(dot);
      }
      gg._node = node;
      return gg;
    }

    // A link: a dashed arrow bowing away from the tree, with its words in the middle.
    function linkEl(l) {
      var a = byBid(l.from), b = byBid(l.to);
      if (!a || !b || !a._vis || !b._vis) return null;
      var x1 = a._x, y1 = a._y, x2 = b._x, y2 = b._y;
      var dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy) || 1;
      // Start and end at the boxes' edges.
      function edge(n, ux, uy) { var t = Math.min(n._w / 2 / (Math.abs(ux) || 1e-6), n._h / 2 / (Math.abs(uy) || 1e-6)); return { x: n._x + ux * t, y: n._y + uy * t }; }
      var p1 = edge(a, dx / len, dy / len), p2 = edge(b, -dx / len, -dy / len);
      var bow = Math.min(90, len * 0.3), nx = -dy / len * bow, ny = dx / len * bow;
      var cx = (p1.x + p2.x) / 2 + nx, cy = (p1.y + p2.y) / 2 + ny;
      var gg = svg('g', { class: 'mm-link' + (l === selLink ? ' sel' : '') });
      gg.appendChild(svg('path', { d: 'M' + p1.x + ',' + p1.y + ' Q' + cx + ',' + cy + ' ' + p2.x + ',' + p2.y, 'marker-end': 'url(#mm-arrow)', class: 'line' }));
      gg.appendChild(svg('path', { d: 'M' + p1.x + ',' + p1.y + ' Q' + cx + ',' + cy + ' ' + p2.x + ',' + p2.y, class: 'hit' }));
      var mx = (p1.x + 2 * cx + p2.x) / 4, my = (p1.y + 2 * cy + p2.y) / 4;
      if (l.label) {
        var w = measure(l.label, '12px system-ui, sans-serif') + 14;
        gg.appendChild(svg('rect', { x: mx - w / 2, y: my - 11, width: w, height: 22, rx: 11, class: 'tag' }));
        var t = svg('text', { x: mx, y: my + 4, 'text-anchor': 'middle' });
        t.textContent = l.label;
        gg.appendChild(t);
      } else gg.appendChild(svg('circle', { cx: mx, cy: my, r: 6, class: 'tag' }));
      gg._link = l;
      return gg;
    }

    // A brace over side-by-side branches (with everything under them) and its comment.
    function braceEl(r) {
      var a = byBid(r.from), b = byBid(r.to);
      if (!a || !b || !a._vis || !b._vis) return null;
      var p = parentOf(a);
      var i = p.children.indexOf(a), j = p.children.indexOf(b), dir = a._dir;
      var top = Infinity, bot = -Infinity, out = dir > 0 ? -Infinity : Infinity;
      p.children.slice(Math.min(i, j), Math.max(i, j) + 1).forEach(function (c) {
        walk(c, function (n) {
          if (!n._vis) return;
          top = Math.min(top, n._y - n._h / 2 - n._top);
          bot = Math.max(bot, n._y + n._h / 2);
          out = dir > 0 ? Math.max(out, n._x + n._w / 2 + (n.children.length ? 18 : 0)) : Math.min(out, n._x - n._w / 2 - (n.children.length ? 18 : 0));
        });
      });
      var x0 = out + dir * 8, d = dir * Math.min(10, (bot - top) / 4), ym = (top + bot) / 2;
      var gg = svg('g', { class: 'mm-group' + (r === selGroup ? ' sel' : '') });
      var path = 'M' + x0 + ',' + top + ' Q' + (x0 + d) + ',' + top + ' ' + (x0 + d) + ',' + (top + Math.abs(d)) + ' L' + (x0 + d) + ',' + (ym - Math.abs(d)) +
        ' Q' + (x0 + d) + ',' + ym + ' ' + (x0 + 2 * d) + ',' + ym + ' Q' + (x0 + d) + ',' + ym + ' ' + (x0 + d) + ',' + (ym + Math.abs(d)) +
        ' L' + (x0 + d) + ',' + (bot - Math.abs(d)) + ' Q' + (x0 + d) + ',' + bot + ' ' + x0 + ',' + bot;
      gg.appendChild(svg('path', { d: path, class: 'line' }));
      gg.appendChild(svg('path', { d: path, class: 'hit' }));
      var lines = r.label ? wrap(r.label, '13px system-ui, sans-serif', 170, 5) : ['…'];
      var w = 0;
      lines.forEach(function (l) { w = Math.max(w, measure(l, '13px system-ui, sans-serif')); });
      w += 18;
      var h = lines.length * 17 + 10, bx = dir > 0 ? x0 + 2 * d + 6 : x0 + 2 * d - 6 - w;
      gg.appendChild(svg('rect', { x: bx, y: ym - h / 2, width: w, height: h, rx: 8, class: 'tag' }));
      lines.forEach(function (l, n) {
        var t = svg('text', { x: bx + w / 2, y: ym - h / 2 + 18 + n * 17, 'text-anchor': 'middle' });
        t.textContent = l;
        gg.appendChild(t);
      });
      gg._group = r;
      return gg;
    }

    function apply() { g.setAttribute('transform', 'translate(' + tx + ',' + ty + ') scale(' + k + ')'); placeEditor(); }

    function fit() {
      var b = g.getBBox(), W = stage.clientWidth, Hh = stage.clientHeight;
      if (!b.width) { tx = W / 2; ty = Hh / 2; k = 1; apply(); return; }
      k = Math.min(1.3, Math.max(0.2, Math.min((W - 30) / b.width, (Hh - 30) / b.height)));
      tx = W / 2 - (b.x + b.width / 2) * k;
      ty = Hh / 2 - (b.y + b.height / 2) * k;
      apply();
    }

    // Keeps a branch (being written) on the screen, at a readable size.
    function reveal(node) {
      if (!node || node._x == null) return;
      var W = stage.clientWidth, Hh = stage.clientHeight;
      if (k < 0.7) k = 0.9;
      var sx = tx + node._x * k, sy = ty + node._y * k, m = 60;
      if (sx < m || sx > W - m || sy < m || sy > Hh - m) { tx = W / 2 - node._x * k; ty = Hh / 3 - node._y * k; }
      apply();
    }

    // ---- touch: tap selects, drag pans, two fingers zoom, long press drags a branch -------------

    var pts = new Map(), moved = false, last = null, pinch = null, lastTap = 0, press = null, drag = null;
    function graphPoint(cx, cy) { var r = stage.getBoundingClientRect(); return { x: (cx - r.left - tx) / k, y: (cy - r.top - ty) / k }; }
    function hitNode(cx, cy) {
      var t = document.elementFromPoint(cx, cy);
      var ng = t && t.closest ? t.closest('.mm-node') : null;
      return ng ? { node: ng._node, fold: !!t.closest('.mm-fold'), el: ng } : null;
    }

    stage.addEventListener('pointerdown', function (e) {
      if (e.target.classList && e.target.classList.contains('mm-edit')) return;
      try { stage.setPointerCapture(e.pointerId); } catch (x2) {}
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      moved = false;
      last = { x: e.clientX, y: e.clientY };
      clearTimeout(press);
      if (pts.size === 2) {
        var a = Array.from(pts.values());
        pinch = { d: Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y), k: k, tx: tx, ty: ty, mx: (a[0].x + a[1].x) / 2, my: (a[0].y + a[1].y) / 2 };
        return;
      }
      var h = hitNode(e.clientX, e.clientY);
      if (h && branch(h.node) && !opts.readOnly && !mode) {
        press = setTimeout(function () {
          if (moved || pts.size !== 1) return;
          stopEdit(true);
          drag = { node: h.node, ghost: h.el.cloneNode(true), over: null, zone: null };
          drag.ghost.setAttribute('class', drag.ghost.getAttribute('class') + ' ghost');
          g.appendChild(drag.ghost);
          if (navigator.vibrate) try { navigator.vibrate(15); } catch (x3) {}
          dragTo(e.clientX, e.clientY);
        }, 450);
      }
    });

    function dragTo(cx, cy) {
      var p = graphPoint(cx, cy), n = drag.node;
      drag.ghost.setAttribute('transform', 'translate(' + (p.x - n._w / 2 + 12) + ',' + (p.y - n._h / 2 + 12) + ')');
      drag.ghost.style.display = 'none';
      var h = hitNode(cx, cy);
      drag.ghost.style.display = '';
      g.querySelectorAll('.mm-node.drop').forEach(function (x2) { x2.classList.remove('drop', 'z-before', 'z-after', 'z-child'); });
      drag.over = null;
      if (!h || within(h.node, n) || h.node.kind === 'note') return;
      var t = h.node, rel = (p.y - (t._y - t._h / 2)) / t._h;
      var zone = !editable(t) ? (rel < 0.5 ? 'before' : 'after') : t.kind === 'root' ? 'child' : rel < 0.25 ? 'before' : rel > 0.75 ? 'after' : 'child';
      drag.over = t;
      drag.zone = zone;
      h.el.classList.add('drop', 'z-' + zone);
    }

    stage.addEventListener('pointermove', function (e) {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (drag) { dragTo(e.clientX, e.clientY); return; }
      if (pts.size === 2 && pinch) {
        var a = Array.from(pts.values());
        var d = Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y);
        var nk = Math.min(3, Math.max(0.15, pinch.k * d / pinch.d));
        var r = stage.getBoundingClientRect(), mx = pinch.mx - r.left, my = pinch.my - r.top;
        tx = mx - (mx - pinch.tx) * nk / pinch.k;
        ty = my - (my - pinch.ty) * nk / pinch.k;
        k = nk;
        moved = true;
        apply();
        return;
      }
      var dx = e.clientX - last.x, dy = e.clientY - last.y;
      if (Math.abs(dx) + Math.abs(dy) > 2) {
        if (!moved && Math.hypot(dx, dy) < 6) return;
        moved = true;
        clearTimeout(press);
        tx += dx;
        ty += dy;
        last = { x: e.clientX, y: e.clientY };
        apply();
      }
    });

    function up(e) {
      clearTimeout(press);
      var wasMoved = moved;
      pts.delete(e.pointerId);
      if (pts.size < 2) pinch = null;
      if (drag) {
        var d = drag;
        drag = null;
        d.ghost.remove();
        if (!d.over || !move(d.node, d.over, d.zone)) draw();
        return;
      }
      if (wasMoved || pts.size) return;
      var t = document.elementFromPoint(e.clientX, e.clientY);
      if (t && t.classList && t.classList.contains('mm-edit')) return;
      var lg = t && t.closest ? t.closest('.mm-link, .mm-group') : null;
      if (lg && !mode) { stopEdit(true); select(null); if (lg._link) selLink = lg._link; else selGroup = lg._group; draw(); return; }
      var h = hitNode(e.clientX, e.clientY);
      if (mode) { finishMode(h && h.node); return; }
      if (!h) { stopEdit(true); select(null); draw(); return; }
      var node = h.node;
      if (h.fold) { fold(node); return; }
      var now = Date.now();
      var dbl = selected === node && now - lastTap < 400;
      lastTap = now;
      if (editing && editing.node === node) return;
      stopEdit(true);
      select(node);
      draw();
      if (dbl) { if (node.kind === 'note') opts.openNote(node.note); else edit(node); }
    }
    stage.addEventListener('pointerup', up);
    stage.addEventListener('pointercancel', function (e) {
      clearTimeout(press);
      pts.delete(e.pointerId);
      pinch = null;
      if (drag) { drag.ghost.remove(); drag = null; draw(); }
    });
    stage.addEventListener('wheel', function (e) {
      e.preventDefault();
      var r = stage.getBoundingClientRect(), mx = e.clientX - r.left, my = e.clientY - r.top;
      var nk = Math.min(3, Math.max(0.15, k * (e.deltaY < 0 ? 1.12 : 1 / 1.12)));
      tx = mx - (mx - tx) * nk / k;
      ty = my - (my - ty) * nk / k;
      k = nk;
      apply();
    }, { passive: false });

    // Keyboard (tablets, PCs): Enter / Tab add, F2 or typing edits, Delete removes, arrows move.
    function onKey(e) {
      if (editing || document.querySelector('.sheet-back') || opts.readOnly && e.key !== 'Escape') return;
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') { e.preventDefault(); back(); return; }
      var n = selected;
      if (e.key === 'Escape') { if (mode) { mode = null; draw(); } return; }
      if (!n) return;
      if (e.key === 'Enter') { e.preventDefault(); if (n === tree) add(n, true); else add(n, false); }
      else if (e.key === 'Tab') { e.preventDefault(); if (e.shiftKey) outdent(n); else add(n, true); }
      else if (e.key === 'F2') { e.preventDefault(); edit(n); }
      else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); if (selLink) dropRef(links, selLink); else if (selGroup) dropRef(groups, selGroup); else remove(n); }
      else if (/^Arrow/.test(e.key)) {
        e.preventDefault();
        var p = parentOf(n), sib = p ? p.children : [], i = sib.indexOf(n), dir = n._dir || 1;
        var to = e.key === 'ArrowUp' ? sib[i - 1] : e.key === 'ArrowDown' ? sib[i + 1]
          : (e.key === 'ArrowRight') === (dir > 0) ? (!collapsed.has(n) && n.children[0]) : p;
        if (to) { select(to); draw(); reveal(to); }
      } else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey && editable(n)) { e.preventDefault(); edit(n, false, e.key); }
    }
    document.addEventListener('keydown', onKey);

    function close() {
      stopEdit(true);
      document.removeEventListener('keydown', onKey);
      root.Mindmap.current = null;
      wrapEl.remove();
      if (opts.onClose) opts.onClose();
    }

    selected = tree;
    cur = snapshot();
    draw();
    fit();
    root.Mindmap.current = { close: close, tree: tree, edit: function () { edit(selected); } };
    if (opts.startEditing && !opts.readOnly) setTimeout(function () { edit(tree, false); }, 80);
  }

  root.Mindmap = { open: open, current: null };
})(window);
