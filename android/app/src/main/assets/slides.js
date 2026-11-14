'use strict';
// Simple slides: a note's body split by "---" lines (the way Obsidian's Advanced Slides and Marp
// read it), each part one slide: a heading for its title, then text, lists and pictures
// (![[Memo/_files/x.jpg]]). Pictures alone on a slide fill it; with text they sit beside it.
(function (root) {
  var SEP = /^\s*---\s*$/, H = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
  var IMG_LINE = /^\s*!\[\[(?:Memo\/_files\/)?([A-Za-z0-9_.-]+\.(?:jpe?g|png|gif|webp|bmp|heic|heif))(?:\|[^\]]*)?\]\]\s*$/i;

  function trim(a) {
    var s = 0, e = a.length;
    while (s < e && !a[s].trim()) s++;
    while (e > s && !a[e - 1].trim()) e--;
    return a.slice(s, e);
  }

  // A separator is "---" with a blank line (or the start) before it, outside code.
  function split(body) {
    var lines = String(body || '').replace(/\r\n/g, '\n').split('\n'), out = [[]], fence = null;
    lines.forEach(function (l, i) {
      if (fence) { if (l.trim().indexOf(fence) === 0) fence = null; out[out.length - 1].push(l); return; }
      var f = /^\s*(```|~~~)/.exec(l);
      if (f) fence = f[1];
      var cur = out[out.length - 1];
      if (!f && SEP.test(l) && (!trim(cur).length || !cur.length || !cur[cur.length - 1].trim())) { out.push([]); return; }
      cur.push(l);
    });
    var slides = out.map(function (a) { return trim(a).join('\n'); });
    while (slides.length > 1 && !slides[slides.length - 1]) slides.pop();
    return slides.length ? slides : [''];
  }

  function join(slides) { return slides.map(function (s) { return String(s || '').trim(); }).join('\n\n---\n\n'); }

  // title (first line when it is a heading), its level, the rest as text, the pictures alone on a line.
  function parts(slide) {
    var lines = String(slide || '').split('\n'), title = '', level = 0;
    var m = lines.length ? H.exec(lines[0]) : null;
    if (m) { title = m[2]; level = m[1].length; lines = lines.slice(1); }
    var images = [], text = [];
    lines.forEach(function (l) { var i = IMG_LINE.exec(l); if (i) images.push(i[1]); else text.push(l); });
    return { title: title, level: level, text: trim(text).join('\n'), images: images, rest: trim(lines).join('\n') };
  }

  function compose(title, level, rest) {
    var t = String(title || '').replace(/\n/g, ' ').trim();
    return ((t ? new Array((level || 2) + 1).join('#') + ' ' + t : '') + (rest.trim() ? (t ? '\n\n' : '') + rest.trim() : '')).trim();
  }

  var api = { split: split, join: join, parts: parts, compose: compose };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; return; }

  // ---- drawing a slide (960 × 540, scaled to its box) ------------------------------------------

  function render(slide, box) {
    var el = App.el, p = parts(slide);
    var s = el('div', 'sl-slide' + (!p.text && !p.images.length ? ' only-title' : '') + (p.images.length && !p.text ? ' only-pics' : '') + (p.images.length && p.text ? ' with-pics' : '') + (p.images.length > 1 ? ' pics-' + Math.min(4, p.images.length) : ''));
    if (p.title) { var h = el('h1', 'sl-h'); h.innerHTML = MD.inline(p.title); s.appendChild(h); }
    var main = el('div', 'sl-main');
    if (p.text) { var t = el('div', 'sl-text'); t.innerHTML = MD.html(p.text); main.appendChild(t); }
    if (p.images.length) {
      var g = el('div', 'sl-pics');
      p.images.slice(0, 4).forEach(function (n) { var im = el('img'); im.src = 'media/' + n; im.alt = ''; im.onerror = function () { if (im.src.indexOf('thumb/') < 0) im.src = 'thumb/' + n; }; g.appendChild(im); });
      main.appendChild(g);
    }
    s.appendChild(main);
    box.innerHTML = '';
    box.appendChild(s);
    var fit = function () { var w = box.clientWidth, h2 = box.clientHeight; if (!w) return; var k = Math.min(w / 960, (h2 || w * 9 / 16) / 540); s.style.transform = 'scale(' + k + ')'; s.style.left = (w - 960 * k) / 2 + 'px'; s.style.top = ((h2 || w * 9 / 16) - 540 * k) / 2 + 'px'; };
    fit();
    return fit;
  }

  // ---- the screen ------------------------------------------------------------------------------

  function open(opts) {
    var el = App.el, slides = split(opts.body), title = opts.title || '', ro = !!opts.readOnly, fits = [];
    var wrap = el('div', 'slides-view');
    var bar = el('div', 'sl-bar');
    var x = el('button', 'icon', '←');
    x.title = '閉じる';
    x.onclick = function () { close(); };
    bar.appendChild(x);
    var head = el('b', 'grow sl-titlebar');
    head.title = '名前を変える';
    head.onclick = function () { if (!ro) rename(); };
    bar.appendChild(head);
    var play = el('button', 'sl-play', '▶ 再生');
    play.title = '最初から再生';
    play.onclick = function () { present(0); };
    bar.appendChild(play);
    var addB = el('button', 'icon', '＋');
    addB.title = 'スライドを足す';
    addB.onclick = function () { slides.push(''); save(); edit(slides.length - 1, true); };
    if (!ro) bar.appendChild(addB);
    wrap.appendChild(bar);
    var list = el('div', 'sl-list');
    wrap.appendChild(list);
    document.body.appendChild(wrap);

    function save() { opts.onChange(title, join(slides)); paint(); }

    function paint() {
      head.textContent = title || '（名前のないスライド）';
      list.innerHTML = '';
      fits = [];
      slides.forEach(function (sl, i) {
        var item = el('div', 'sl-item');
        var num = el('div', 'sl-num');
        num.appendChild(el('span', '', String(i + 1)));
        var more = el('button', 'icon sl-more', '⋮');
        more.title = 'このスライド';
        more.onclick = function (e) { e.stopPropagation(); menu(i, more); };
        num.appendChild(more);
        item.appendChild(num);
        var box = el('div', 'sl-thumb');
        box.dataset.i = i;
        box.onclick = function () { if (ro) present(i); else edit(i); };
        item.appendChild(box);
        list.appendChild(item);
        fits.push([sl, box]);
      });
      requestAnimationFrame(function () { fits.forEach(function (f) { render(f[0], f[1]); }); });
    }

    function menu(i, anchor) {
      var items = [['▶ ここから再生', function () { present(i); }]];
      if (!ro) {
        items.push(['✎ 編集', function () { edit(i); }]);
        if (i > 0) items.push(['↑ 前へ', function () { var s = slides.splice(i, 1)[0]; slides.splice(i - 1, 0, s); save(); }]);
        if (i < slides.length - 1) items.push(['↓ 後ろへ', function () { var s = slides.splice(i, 1)[0]; slides.splice(i + 1, 0, s); save(); }]);
        items.push(['⧉ 複製', function () { slides.splice(i + 1, 0, slides[i]); save(); }]);
        items.push(['＋ 後ろに新しいスライド', function () { slides.splice(i + 1, 0, ''); save(); edit(i + 1, true); }]);
        if (slides.length > 1) items.push(['🗑 削除', function () { slides.splice(i, 1); save(); }, true]);
      }
      App.menu(anchor, items);
    }

    // One slide: title, text (Markdown: lists, **bold**…), pictures — with a live preview.
    function edit(i, fresh) {
      var p = parts(slides[i]);
      var body = el('div', 'sl-edit');
      var tIn = el('input', 'wide sl-etitle');
      tIn.value = p.title;
      tIn.placeholder = 'タイトル';
      body.appendChild(tIn);
      var ta = el('textarea', 'wide sl-etext');
      ta.rows = 6;
      ta.value = p.rest;
      ta.placeholder = '本文（- で箇条書き、**太字** など）';
      body.appendChild(ta);
      var tools = el('div', 'sl-etools');
      var bullet = el('button', '', '• 箇条書き');
      bullet.onclick = function () { insertAt(ta, (ta.value && !/\n$/.test(ta.value.slice(0, ta.selectionStart)) && ta.selectionStart ? '\n' : '') + '- '); preview(); };
      tools.appendChild(bullet);
      if (opts.pickImage) {
        var pic = el('button', '', '🖼 写真を入れる');
        pic.onclick = function () {
          var at = ta.selectionStart;
          opts.pickImage(function (files) {
            var add = (files || []).filter(function (f) { return /\.(jpe?g|png|gif|webp|bmp|heic|heif)$/i.test(f.name); }).map(function (f) { return '![[Memo/_files/' + f.name + ']]'; });
            if (!add.length) return;
            ta.selectionStart = ta.selectionEnd = Math.min(at, ta.value.length);
            insertAt(ta, (ta.value.slice(0, at).trim() ? '\n\n' : '') + add.join('\n') + '\n');
            preview();
          });
        };
        tools.appendChild(pic);
      }
      body.appendChild(tools);
      var pv = el('div', 'sl-thumb sl-preview');
      body.appendChild(pv);
      function current() { return compose(tIn.value, p.level || (i === 0 ? 1 : 2), ta.value); }
      function preview() { render(current(), pv); }
      tIn.oninput = preview;
      ta.oninput = preview;
      App.sheet(fresh ? '新しいスライド' : 'スライド ' + (i + 1), body, [['キャンセル', function () { if (fresh && !slides[i] && slides.length > 1) { slides.splice(i, 1); save(); } }], ['決定', function () {
        var v = current();
        if (fresh && !v && slides.length > 1) { slides.splice(i, 1); save(); return; }
        slides[i] = v;
        save();
      }, 'primary']], { wide: true });
      requestAnimationFrame(preview);
      if (fresh) setTimeout(function () { tIn.focus(); }, 50);
    }

    function insertAt(ta, s) {
      var a = ta.selectionStart, b = ta.selectionEnd;
      ta.value = ta.value.slice(0, a) + s + ta.value.slice(b);
      ta.selectionStart = ta.selectionEnd = a + s.length;
      ta.focus();
    }

    function rename(then) {
      var body = el('div');
      var input = el('input', 'wide');
      input.value = title;
      input.placeholder = 'スライドの名前（メモのタイトル）';
      body.appendChild(input);
      App.sheet('名前', body, [['キャンセル'], ['決定', function () { title = input.value.trim(); save(); if (then) setTimeout(then, 0); }, 'primary']]);
      setTimeout(function () { input.focus(); }, 50);
    }

    // Showing: full screen, tap the right side (or swipe left) for the next slide.
    function present(from) {
      var i = from, show = el('div', 'sl-show');
      var stage = el('div', 'sl-stage');
      show.appendChild(stage);
      var count = el('div', 'sl-count');
      show.appendChild(count);
      var shut = el('button', 'icon sl-close', '✕');
      shut.title = '再生をやめる';
      show.appendChild(shut);
      document.body.appendChild(show);
      var fit = null;
      function go(n) {
        i = Math.max(0, Math.min(slides.length - 1, n));
        fit = render(slides[i], stage);
        count.textContent = (i + 1) + ' / ' + slides.length;
      }
      function end() {
        document.removeEventListener('keydown', key);
        window.removeEventListener('resize', onResize);
        try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
        try { if (document.fullscreenElement) document.exitFullscreen(); } catch (e) {}
        show.remove();
        App.native('present', { on: false });
        root.Slides.showing = null;
      }
      function key(e) {
        if (e.key === 'ArrowRight' || e.key === ' ' || e.key === 'PageDown' || e.key === 'Enter') { e.preventDefault(); go(i + 1); }
        else if (e.key === 'ArrowLeft' || e.key === 'PageUp' || e.key === 'Backspace') { e.preventDefault(); go(i - 1); }
        else if (e.key === 'Escape') end();
      }
      function onResize() { if (fit) fit(); }
      var sx = null;
      show.addEventListener('pointerdown', function (e) { sx = e.clientX; });
      show.addEventListener('pointerup', function (e) {
        if (e.target === shut) return;
        var dx = sx == null ? 0 : e.clientX - sx;
        if (Math.abs(dx) > 40) go(i + (dx < 0 ? 1 : -1));
        else go(i + (e.clientX > window.innerWidth / 3 ? 1 : -1));
        sx = null;
      });
      shut.onclick = function (e) { e.stopPropagation(); end(); };
      document.addEventListener('keydown', key);
      window.addEventListener('resize', onResize);
      // Full screen and sideways when the phone lets us (slides are 16:9).
      try {
        if (show.requestFullscreen) show.requestFullscreen().then(function () {
          try { if (screen.orientation && screen.orientation.lock) screen.orientation.lock('landscape').catch(function () {}); } catch (e2) {}
        }).catch(function () {});
      } catch (e) {}
      App.native('present', { on: true });
      root.Slides.showing = { close: end };
      go(i);
    }

    function close() {
      if (root.Slides.showing) root.Slides.showing.close();
      root.Slides.current = null;
      window.removeEventListener('resize', refit);
      wrap.remove();
      if (opts.onClose) opts.onClose();
    }
    function refit() { fits.forEach(function (f) { render(f[0], f[1]); }); }
    window.addEventListener('resize', refit);

    paint();
    root.Slides.current = { close: close };
    if (opts.startNew && !ro) setTimeout(function () { if (!title) rename(function () { edit(0, true); }); else edit(0, true); }, 60);
  }

  api.open = open;
  api.render = render;
  api.current = null;
  api.showing = null;
  root.Slides = api;
})(typeof window !== 'undefined' ? window : this);
