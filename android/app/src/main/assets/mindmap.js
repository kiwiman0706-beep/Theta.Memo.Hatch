'use strict';
// Mind map of a note: the note's title in the middle, its headings and (nested) list items as
// branches, paragraphs and attachments as leaves, child notes as dashed branches. It is the same
// tree as the outline (MD.outline), so editing the map rewrites the note's Markdown — Obsidian
// sees ordinary headings and indented lists.
(function (root) {
  var SVGNS = 'http://www.w3.org/2000/svg';
  var BRANCH = ['#1a73e8', '#d93025', '#188038', '#f29900', '#a142f4', '#12b5cb', '#e8710a', '#9334e6'];
  var GAP_X = 46, GAP_Y = 14;

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
  function wrap(text, font, max) {
    var lines = [], cur = '';
    Array.from(text).forEach(function (ch) {
      if (measure(cur + ch, font) > max && cur) { lines.push(cur); cur = ch; }
      else cur += ch;
    });
    if (cur || !lines.length) lines.push(cur);
    return lines.slice(0, 6);
  }

  function label(node) {
    if (node.kind === 'root') return node.text || '（タイトルなし）';
    if (node.kind === 'note') return '📄 ' + (node.text || '（タイトルなし）');
    if (node.kind === 'h' || node.kind === 'li') {
      var t = MD.task(node.text);
      var s = MD.plain(t.text).trim();
      var marks = (t.imp ? ' ★' : '') + (t.urg ? ' ⚡' : '') + (t.due ? ' 📅' + GTD.shortDate(t.due) : '');
      return (node.task ? (node.checked ? '☑ ' : '☐ ') : '') + (s || '（空）') + marks;
    }
    if (node.kind === 'p') { var p = MD.plain(node.lines.join(' ')).trim(); return p.length > 60 ? p.slice(0, 60) + '…' : p; }
    var first = node.lines[0] || '';
    var f = MD.fileLine(first);
    if (f) return { image: '🖼 写真', ink: '✎ 手書き', audio: '🎤 録音', video: '🎬 動画', pdf: '📄 ' + (f.label || 'PDF'), file: '📎 ' + (f.label || 'ファイル') }[f.kind];
    if (/^\s*\|/.test(first)) return '▦ 表';
    if (/^\s*(```|~~~)/.test(first)) return '‹/› コード';
    return '…';
  }

  function open(opts) {
    var tree = MD.outline(opts.body, opts.title);
    (opts.children || []).forEach(function (c) { tree.children.push({ kind: 'note', note: c, text: MD.title(c), children: [] }); });
    var collapsed = new Set(), selected = null, k = 1, tx = 0, ty = 0;

    var el = App.el;
    var wrapEl = el('div', 'mindmap');
    var bar = el('div', 'mm-bar');
    var x = el('button', 'icon', '←');
    x.title = '閉じる';
    x.onclick = function () { close(); };
    bar.appendChild(x);
    bar.appendChild(el('b', 'grow', 'マインドマップ'));
    var fitB = el('button', 'icon', '⤢');
    fitB.title = '全体を表示';
    fitB.onclick = function () { fit(); };
    bar.appendChild(fitB);
    wrapEl.appendChild(bar);
    var stage = el('div', 'mm-stage');
    var s = svg('svg', { width: '100%', height: '100%' });
    var g = svg('g', {});
    s.appendChild(g);
    stage.appendChild(s);
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
    var tEdit = tool('✎', '文字を直す', function () { edit(selected); });
    var tTask = tool('☑', 'チェックボックス', function () { toggleTask(selected); });
    var tFold = tool('▸', '折りたたむ／ひらく', function () { fold(selected); });
    var tOpen = tool('📄', '子メモを開く', function () { if (selected && selected.kind === 'note') opts.openNote(selected.note); });
    var tDel = tool('🗑', '削除', function () { remove(selected); });

    function paintTools() {
      var n = selected, ro = opts.readOnly;
      var kind = n ? n.kind : '';
      tChild.disabled = ro || !n || !/^(root|h|li)$/.test(kind);
      tSib.disabled = ro || !n || !/^(h|li)$/.test(kind);
      tEdit.disabled = ro || !n || !/^(root|h|li)$/.test(kind);
      tTask.disabled = ro || !n || kind !== 'li';
      tFold.disabled = !n || !n.children.length;
      tOpen.style.display = kind === 'note' ? '' : 'none';
      tDel.disabled = ro || !n || /^(root|note)$/.test(kind);
    }

    // ---- tree edits --------------------------------------------------------------------------

    function parentOf(node, from) {
      from = from || tree;
      for (var i = 0; i < from.children.length; i++) {
        if (from.children[i] === node) return from;
        var p = parentOf(node, from.children[i]);
        if (p) return p;
      }
      return null;
    }

    function commit() {
      var copy = { kind: 'root', text: tree.text, children: tree.children.filter(function (c) { return c.kind !== 'note'; }) };
      opts.onChange(tree.text, MD.outlineBody(copy));
      draw();
    }

    function newNodeUnder(parent) {
      if (parent.kind === 'root') {
        var hs = parent.children.filter(function (c) { return c.kind === 'h'; });
        if (hs.length) return { kind: 'h', level: Math.min.apply(null, hs.map(function (h) { return h.level; })), text: '', children: [] };
        return { kind: 'li', marker: '-', text: '', children: [] };
      }
      var lis = parent.children.filter(function (c) { return c.kind === 'li'; });
      return { kind: 'li', marker: '-', task: lis.length > 0 && lis.every(function (c) { return c.task; }), checked: false, text: '', children: [] };
    }

    function add(node, asChild) {
      if (!node) return;
      var parent = asChild ? node : parentOf(node);
      if (!parent) return;
      var nu = asChild ? newNodeUnder(node) : node.kind === 'h' ? { kind: 'h', level: node.level, text: '', children: [] } : { kind: 'li', marker: '-', task: !!node.task, checked: false, text: '', children: [] };
      // Lists come before the first note branch; headings keep their place.
      var at = asChild ? parent.children.length : parent.children.indexOf(node) + 1;
      if (parent === tree) { var firstNote = -1; parent.children.forEach(function (c, i) { if (c.kind === 'note' && firstNote < 0) firstNote = i; }); if (firstNote >= 0 && at > firstNote) at = firstNote; }
      // A list item can not follow a heading's own children at the same level: put it before sub-headings.
      if (nu.kind === 'li' && parent.kind !== 'li') {
        var firstH = -1;
        parent.children.forEach(function (c, i) { if (c.kind === 'h' && firstH < 0 && i >= 0) firstH = i; });
        if (firstH >= 0 && at > firstH) at = firstH;
      }
      parent.children.splice(at, 0, nu);
      collapsed.delete(parent);
      selected = nu;
      draw();
      edit(nu, true);
    }

    function edit(node, fresh) {
      if (!node || !/^(root|h|li)$/.test(node.kind)) return;
      var body = el('div');
      var input = el('input', 'wide');
      var t = node.kind === 'root' ? { text: node.text } : MD.task(node.text);
      input.value = node.kind === 'root' ? node.text : t.text;
      input.placeholder = node.kind === 'root' ? 'タイトル' : '内容';
      body.appendChild(input);
      var done = false;
      function save() {
        done = true;
        var v = input.value.trim();
        if (fresh && !v) { var p = parentOf(node); if (p) p.children.splice(p.children.indexOf(node), 1); selected = p; draw(); return; }
        if (node.kind === 'root') node.text = v;
        else { t.text = v; node.text = MD.taskText(t); }
        commit();
      }
      input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); closeSheet(); save(); } });
      var closeSheet = App.sheet(node.kind === 'root' ? 'タイトル' : fresh ? '追加' : '文字を直す', body,
        [['キャンセル', function () { done = true; if (fresh) { var p = parentOf(node); if (p) p.children.splice(p.children.indexOf(node), 1); selected = p; draw(); } }], ['決定', save, 'primary']],
        { onClose: function () { if (!done && fresh) { var p = parentOf(node); if (p && p.children.indexOf(node) >= 0) { p.children.splice(p.children.indexOf(node), 1); selected = p; draw(); } } } });
      setTimeout(function () { input.focus(); input.select(); }, 50);
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
      if (node.children.length && !confirm('この枝（' + (node.children.length) + ' 個の子を含む）を削除しますか？')) return;
      var p = parentOf(node);
      p.children.splice(p.children.indexOf(node), 1);
      selected = p;
      commit();
    }

    // ---- layout -----------------------------------------------------------------------------

    function sizeOf(node, depth) {
      var font = depth === 0 ? '600 17px system-ui, sans-serif' : node.kind === 'p' ? 'italic 13px system-ui, sans-serif' : (node.kind === 'h' ? '600 ' : '') + '14px system-ui, sans-serif';
      var max = depth === 0 ? 240 : 200;
      node._font = font;
      node._lines = wrap(label(node), font, max);
      var w = 0;
      node._lines.forEach(function (l) { w = Math.max(w, measure(l, font)); });
      node._w = Math.max(depth === 0 ? 90 : 40, w + (depth === 0 ? 32 : 22));
      node._lh = depth === 0 ? 22 : 19;
      node._h = node._lines.length * node._lh + (depth === 0 ? 22 : 14);
      var kids = collapsed.has(node) ? [] : node.children;
      var total = 0;
      kids.forEach(function (c, i) { sizeOf(c, depth + 1); total += c._sh + (i ? GAP_Y : 0); });
      node._sh = Math.max(node._h, total);
    }

    function place(node, x, y, dir, color, depth) {
      node._x = x;
      node._y = y;
      node._dir = dir;
      node._color = color;
      node._depth = depth;
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
      sizeOf(tree, 0);
      tree._x = 0;
      tree._y = 0;
      tree._depth = 0;
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
      var edges = svg('g', { class: 'mm-edges' }), nodes = svg('g', {});
      g.appendChild(edges);
      g.appendChild(nodes);
      (function walk(node) {
        var kids = collapsed.has(node) ? [] : node.children;
        kids.forEach(function (c) {
          var x1 = node._x + c._dir * node._w / 2, y1 = node._y, x2 = c._x - c._dir * c._w / 2, y2 = c._y, mx = (x1 + x2) / 2;
          edges.appendChild(svg('path', { d: 'M' + x1 + ',' + y1 + ' C' + mx + ',' + y1 + ' ' + mx + ',' + y2 + ' ' + x2 + ',' + y2,
            stroke: c._color, 'stroke-width': Math.max(1.5, 4 - c._depth), fill: 'none', 'stroke-opacity': 0.8,
            'stroke-dasharray': c.kind === 'note' ? '5 4' : '' }));
          walk(c);
        });
        nodes.appendChild(nodeEl(node));
      })(tree);
      paintTools();
    }

    function nodeEl(node) {
      var gg = svg('g', { class: 'mm-node k-' + node.kind + (node === selected ? ' sel' : '') + (node.checked ? ' checked' : ''), transform: 'translate(' + (node._x - node._w / 2) + ',' + (node._y - node._h / 2) + ')' });
      var root0 = node._depth === 0;
      var rect = svg('rect', { width: node._w, height: node._h, rx: root0 ? 14 : 9, ry: root0 ? 14 : 9 });
      if (!root0) {
        rect.setAttribute('stroke', node._color);
        rect.setAttribute('fill', node.kind === 'p' || node.kind === 'raw' ? 'var(--surface)' : node._color);
        rect.setAttribute('fill-opacity', node.kind === 'p' || node.kind === 'raw' ? 1 : node._depth === 1 ? 0.22 : 0.1);
        if (node.kind === 'note') rect.setAttribute('stroke-dasharray', '5 4');
      }
      gg.appendChild(rect);
      node._lines.forEach(function (l, i) {
        var t = svg('text', { x: node._w / 2, y: (root0 ? 11 : 7) + node._lh * (i + 0.78), 'text-anchor': 'middle' });
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
        dot.dataset.fold = '1';
        gg.appendChild(dot);
      }
      gg._node = node;
      return gg;
    }

    function apply() { g.setAttribute('transform', 'translate(' + tx + ',' + ty + ') scale(' + k + ')'); }

    function fit() {
      var b = g.getBBox(), W = stage.clientWidth, Hh = stage.clientHeight;
      if (!b.width) { tx = W / 2; ty = Hh / 2; k = 1; apply(); return; }
      k = Math.min(1.3, Math.max(0.2, Math.min((W - 30) / b.width, (Hh - 30) / b.height)));
      tx = W / 2 - (b.x + b.width / 2) * k;
      ty = Hh / 2 - (b.y + b.height / 2) * k;
      apply();
    }

    // ---- touch: tap selects, drag pans, two fingers zoom ----------------------------------------

    var pts = new Map(), moved = false, last = null, pinch = null, lastTap = 0;
    stage.addEventListener('pointerdown', function (e) {
      try { stage.setPointerCapture(e.pointerId); } catch (x2) {}
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      moved = false;
      last = { x: e.clientX, y: e.clientY };
      if (pts.size === 2) {
        var a = Array.from(pts.values());
        pinch = { d: Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y), k: k, tx: tx, ty: ty, mx: (a[0].x + a[1].x) / 2, my: (a[0].y + a[1].y) / 2 };
      }
    });
    stage.addEventListener('pointermove', function (e) {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
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
        if (!moved && Math.hypot(e.clientX - last.x, e.clientY - last.y) < 6) return;
        moved = true;
        tx += dx;
        ty += dy;
        last = { x: e.clientX, y: e.clientY };
        apply();
      }
    });
    function up(e) {
      var wasMoved = moved;
      pts.delete(e.pointerId);
      if (pts.size < 2) pinch = null;
      if (wasMoved || pts.size) return;
      var t = document.elementFromPoint(e.clientX, e.clientY);
      var ng = t && t.closest ? t.closest('.mm-node') : null;
      if (!ng) { selected = null; draw(); return; }
      var node = ng._node;
      if (t.closest('.mm-fold')) { fold(node); return; }
      var now = Date.now();
      var dbl = selected === node && now - lastTap < 400;
      lastTap = now;
      selected = node;
      draw();
      if (dbl) { if (node.kind === 'note') opts.openNote(node.note); else if (!opts.readOnly) edit(node); }
    }
    stage.addEventListener('pointerup', up);
    stage.addEventListener('pointercancel', function (e) { pts.delete(e.pointerId); pinch = null; });
    stage.addEventListener('wheel', function (e) {
      e.preventDefault();
      var r = stage.getBoundingClientRect(), mx = e.clientX - r.left, my = e.clientY - r.top;
      var nk = Math.min(3, Math.max(0.15, k * (e.deltaY < 0 ? 1.12 : 1 / 1.12)));
      tx = mx - (mx - tx) * nk / k;
      ty = my - (my - ty) * nk / k;
      k = nk;
      apply();
    }, { passive: false });

    function close() {
      root.Mindmap.current = null;
      wrapEl.remove();
      if (opts.onClose) opts.onClose();
    }

    selected = tree;
    draw();
    fit();
    root.Mindmap.current = { close: close, tree: tree };
  }

  root.Mindmap = { open: open, current: null };
})(window);
