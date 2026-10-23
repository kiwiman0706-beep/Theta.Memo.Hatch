'use strict';
// Handwriting canvas (S Pen and other styluses): pressure-sensitive strokes, marker, stroke
// eraser (also the S Pen side button), undo / redo, a page that grows as you write. Strokes are
// kept as JSON (re-editable) next to a white PNG that Obsidian and every other app can show.
(function (root) {
  var W = 1000; // logical width; strokes are stored in these units
  var COLORS = ['#202124', '#1a73e8', '#d93025', '#188038', '#f9ab00', '#a142f4', '#ffffff'];
  var SIZES = { pen: [2, 4, 8], marker: [18, 28, 40], eraser: [16, 30, 60] };

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function open(opts) {
    var data = { v: 1, w: W, h: 1400, strokes: [] };
    try {
      if (opts.strokes) {
        var d = JSON.parse(opts.strokes);
        if (d && d.strokes) data = d;
      }
    } catch (e) {}
    var tool = 'pen', color = COLORS[0], sizeIx = 1, redo = [], penSeen = false, fingerDraws = opts.fingerDraws !== false;
    var wrap = el('div', 'ink');
    var bar = el('div', 'ink-bar');
    var scroller = el('div', 'ink-scroll');
    var canvas = el('canvas', 'ink-canvas');
    scroller.appendChild(canvas);
    wrap.appendChild(bar);
    wrap.appendChild(scroller);
    document.body.appendChild(wrap);
    var ctx = canvas.getContext('2d');
    var dpr = Math.min(2, window.devicePixelRatio || 1), scale = 1;

    function button(label, title, on) {
      var b = el('button', 'ink-btn', label);
      b.title = title;
      b.setAttribute('aria-label', title);
      b.onclick = on;
      bar.appendChild(b);
      return b;
    }
    var bDone = button('✓', '完了', function () { finish(true); });
    bDone.classList.add('ink-done');
    var bPen = button('✎', 'ペン', function () { setTool('pen'); });
    var bMarker = button('▮', 'マーカー', function () { setTool('marker'); });
    var bEraser = button('⌫', '消しゴム', function () { setTool('eraser'); });
    var swatches = COLORS.map(function (c) {
      var b = button('', '色', function () { color = c; if (tool === 'eraser') setTool('pen'); paintBar(); });
      b.classList.add('ink-swatch');
      b.style.background = c;
      b.dataset.c = c;
      return b;
    });
    var bSize = button('●', '太さ', function () { sizeIx = (sizeIx + 1) % 3; paintBar(); });
    button('↶', '元に戻す', function () { if (data.strokes.length) { redo.push(data.strokes.pop()); redraw(); } });
    button('↷', 'やり直す', function () { if (redo.length) { data.strokes.push(redo.pop()); redraw(); } });
    var bFinger = button('☝', '指で描く（オフ：指はスクロール）', function () { fingerDraws = !fingerDraws; if (fingerDraws) penSeen = false; paintBar(); });
    button('🗑', 'すべて消す', function () {
      if (!data.strokes.length || !confirm('手書きをすべて消しますか？')) return;
      redo = data.strokes.slice().reverse();
      data.strokes = [];
      redraw();
    });
    button('✕', '保存せずに閉じる', function () {
      if (data.strokes.length && !confirm('変更を保存せずに閉じますか？')) return;
      finish(false);
    });

    function setTool(t) { tool = t; paintBar(); }
    function paintBar() {
      bPen.classList.toggle('on', tool === 'pen');
      bMarker.classList.toggle('on', tool === 'marker');
      bEraser.classList.toggle('on', tool === 'eraser');
      bFinger.classList.toggle('on', fingerDraws && !penSeen);
      swatches.forEach(function (s) { s.classList.toggle('on', s.dataset.c === color && tool !== 'eraser'); });
      bSize.style.fontSize = (10 + sizeIx * 6) + 'px';
    }

    function resize() {
      var cs = getComputedStyle(scroller);
      var cssW = scroller.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      scale = cssW / W;
      canvas.style.width = cssW + 'px';
      canvas.style.height = Math.round(data.h * scale) + 'px';
      canvas.width = Math.round(cssW * dpr);
      canvas.height = Math.round(data.h * scale * dpr);
      redraw();
    }

    function width(s, p) {
      var base = s.size;
      if (s.tool !== 'pen') return base;
      return base * (0.35 + 0.95 * (p == null ? 0.5 : p));
    }

    function drawStroke(c, s, k) {
      var pts = s.pts;
      if (!pts.length) return;
      c.save();
      c.lineCap = 'round';
      c.lineJoin = 'round';
      c.strokeStyle = s.color;
      c.fillStyle = s.color;
      if (s.tool === 'marker') { c.globalAlpha = 0.35; c.globalCompositeOperation = 'multiply'; }
      if (pts.length === 1) {
        c.beginPath();
        c.arc(pts[0][0] * k, pts[0][1] * k, width(s, pts[0][2]) * k / 2, 0, Math.PI * 2);
        c.fill();
      } else if (s.tool === 'marker') {
        c.lineWidth = s.size * k;
        c.beginPath();
        c.moveTo(pts[0][0] * k, pts[0][1] * k);
        for (var i = 1; i < pts.length; i++) c.lineTo(pts[i][0] * k, pts[i][1] * k);
        c.stroke();
      } else {
        // Pressure changes the width along the stroke: short smoothed segments.
        for (var j = 1; j < pts.length; j++) {
          var a = pts[j - 1], b = pts[j], m0 = j > 1 ? mid(pts[j - 2], a) : a, m1 = mid(a, b);
          c.lineWidth = width(s, (a[2] + b[2]) / 2) * k;
          c.beginPath();
          c.moveTo(m0[0] * k, m0[1] * k);
          c.quadraticCurveTo(a[0] * k, a[1] * k, m1[0] * k, m1[1] * k);
          c.stroke();
        }
        var last = pts[pts.length - 1], pm = mid(pts[pts.length - 2], last);
        c.beginPath();
        c.moveTo(pm[0] * k, pm[1] * k);
        c.lineTo(last[0] * k, last[1] * k);
        c.stroke();
      }
      c.restore();
    }
    function mid(a, b) { return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]; }

    function redraw() {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      // Faint ruled lines to write on (not part of the saved picture).
      ctx.strokeStyle = 'rgba(26,115,232,0.10)';
      ctx.lineWidth = 1;
      for (var y = 60; y < data.h; y += 60) {
        ctx.beginPath();
        ctx.moveTo(0, y * scale * dpr);
        ctx.lineTo(canvas.width, y * scale * dpr);
        ctx.stroke();
      }
      data.strokes.forEach(function (s) { drawStroke(ctx, s, scale * dpr); });
    }

    function point(e) {
      var r = canvas.getBoundingClientRect();
      var p = e.pointerType === 'pen' || e.pressure > 0 && e.pressure !== 0.5 ? e.pressure : 0.5;
      return [Math.round((e.clientX - r.left) / scale * 10) / 10, Math.round((e.clientY - r.top) / scale * 10) / 10, Math.round(p * 100) / 100];
    }

    function eraseAt(p) {
      var rad = SIZES.eraser[sizeIx] / 2, hit = false;
      data.strokes = data.strokes.filter(function (s) {
        for (var i = 0; i < s.pts.length; i++) {
          var dx = s.pts[i][0] - p[0], dy = s.pts[i][1] - p[1];
          if (dx * dx + dy * dy < (rad + s.size / 2) * (rad + s.size / 2)) { hit = true; return false; }
        }
        return true;
      });
      if (hit) redraw();
    }

    function capture(id) { try { canvas.setPointerCapture(id); } catch (x) {} }
    var cur = null, curTool = null, pointerId = null, panId = null, panY = 0;
    canvas.addEventListener('pointerdown', function (e) {
      if (e.pointerType === 'pen' && !penSeen) { penSeen = true; paintBar(); }
      // Once a pen is used, fingers (and the palm) scroll the page instead of drawing.
      if (e.pointerType === 'touch' && !(fingerDraws && !penSeen)) {
        if (panId == null) { panId = e.pointerId; panY = e.clientY; capture(e.pointerId); }
        return;
      }
      if (pointerId != null) return;
      e.preventDefault();
      pointerId = e.pointerId;
      capture(e.pointerId);
      // S Pen side button (barrel, buttons & 2) or the eraser end (buttons & 32) erases.
      curTool = (e.buttons & 2) || (e.buttons & 32) || e.button === 5 ? 'eraser' : tool;
      var p = point(e);
      redo = [];
      if (curTool === 'eraser') { eraseAt(p); return; }
      cur = { tool: curTool, color: color, size: SIZES[curTool][sizeIx], pts: [p] };
      data.strokes.push(cur);
      drawStroke(ctx, cur, scale * dpr);
    });
    canvas.addEventListener('pointermove', function (e) {
      if (e.pointerId === panId) { scroller.scrollTop -= e.clientY - panY; panY = e.clientY; return; }
      if (e.pointerId !== pointerId) return;
      e.preventDefault();
      var evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
      if (!evs.length) evs = [e];
      for (var i = 0; i < evs.length; i++) {
        var p = point(evs[i]);
        if (curTool === 'eraser') { eraseAt(p); continue; }
        var last = cur.pts[cur.pts.length - 1];
        if (Math.abs(last[0] - p[0]) + Math.abs(last[1] - p[1]) < 0.8) continue;
        cur.pts.push(p);
        var n = cur.pts.length;
        drawStroke(ctx, { tool: cur.tool, color: cur.color, size: cur.size, pts: cur.pts.slice(Math.max(0, n - 3)) }, scale * dpr);
      }
      // Writing near the bottom: the page grows.
      if (cur && cur.pts[cur.pts.length - 1][1] > data.h - 250) {
        data.h += 700;
        var keep = scroller.scrollTop;
        resize();
        scroller.scrollTop = keep;
      }
    });
    function end(e) {
      if (e.pointerId === panId) { panId = null; return; }
      if (e.pointerId !== pointerId) return;
      pointerId = null;
      if (cur && cur.tool === 'pen') redraw();
      cur = null;
    }
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('contextmenu', function (e) { e.preventDefault(); });

    function bounds() {
      var maxY = 0;
      data.strokes.forEach(function (s) { s.pts.forEach(function (p) { maxY = Math.max(maxY, p[1] + s.size); }); });
      return maxY;
    }

    function exportPng() {
      var h = Math.min(data.h, Math.max(200, Math.ceil(bounds() + 40)));
      var k = 1.2; // 1200 px wide
      var c = document.createElement('canvas');
      c.width = Math.round(W * k);
      c.height = Math.round(h * k);
      var x = c.getContext('2d');
      x.fillStyle = '#ffffff';
      x.fillRect(0, 0, c.width, c.height);
      data.strokes.forEach(function (s) { drawStroke(x, s, k); });
      return c.toDataURL('image/png');
    }

    function finish(save) {
      window.removeEventListener('resize', resize);
      root.Ink.current = null;
      if (save && data.strokes.length) {
        var copy = { v: 1, w: W, h: Math.max(700, Math.ceil(bounds() + 200)), strokes: data.strokes };
        opts.onDone(exportPng(), JSON.stringify(copy));
      } else if (save && opts.onEmpty) opts.onEmpty();
      else if (opts.onCancel) opts.onCancel();
      wrap.remove();
    }

    window.addEventListener('resize', resize);
    paintBar();
    resize();
    root.Ink.current = { close: function () { finish(true); } };
  }

  root.Ink = { open: open, current: null };
})(window);
