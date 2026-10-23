'use strict';
// The note editor: Keep's simplicity with what Keep lacks — rich text, tables and any file.
// The note is a list of blocks (MD.parse): rich text, check lists, tables, and attachments
// (pictures, handwriting, recordings with their transcript, video, PDF, any file). Everything is
// saved as the note's Markdown body, which is what Obsidian and the other apps read.
(function () {
  var el = function (t, c, x) { return App.el(t, c, x); };
  var td = null;

  function turndown() {
    if (td || !window.TurndownService) return td;
    td = new TurndownService({ headingStyle: 'atx', bulletListMarker: '-', codeBlockStyle: 'fenced', emDelimiter: '*', strongDelimiter: '**', br: '' });
    if (window.turndownPluginGfm) td.use([turndownPluginGfm.strikethrough, turndownPluginGfm.tables]);
    // What you type is Markdown: no backslashes sprinkled into the file.
    td.escape = function (s) { return s; };
    td.addRule('mark', { filter: 'mark', replacement: function (c) { return c ? '==' + c + '==' : ''; } });
    td.addRule('underline', { filter: 'u', replacement: function (c) { return c ? '<u>' + c + '</u>' : ''; } });
    td.addRule('wikilink', {
      filter: function (n) { return n.nodeName === 'SPAN' && n.classList.contains('wikilink'); },
      replacement: function (c) { return '[[' + c + ']]'; }
    });
    td.addRule('mediaImg', {
      filter: function (n) { return n.nodeName === 'IMG' && /^media\//.test(n.getAttribute('src') || ''); },
      replacement: function (c, n) { return '![[' + MD.FILES + n.getAttribute('src').slice(6) + ']]'; }
    });
    td.addRule('strike', { filter: ['s', 'del', 'strike'], replacement: function (c) { return c ? '~~' + c + '~~' : ''; } });
    return td;
  }

  function toMarkdown(html) {
    var t = turndown();
    if (!t) return html.replace(/<br\s*\/?>/g, '\n').replace(/<[^>]+>/g, '');
    return t.turndown(html).replace(/\n{3,}/g, '\n\n').replace(/\s+$/, '');
  }

  function fmtDuration(s) {
    s = Math.max(0, Math.round(s));
    return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2);
  }

  var Editor = (window.Editor = { current: null });

  Editor.open = function (note, opts) {
    if (Editor.current) Editor.current.close();
    var n = JSON.parse(JSON.stringify(note));
    var blocks = MD.parse(n.body);
    var isNew = !!opts.isNew, saved = !isNew, readOnly = n.trashed > 0;
    var focusIndex = -1, saveTimer = null, sourceMode = false, recording = null;

    var root = el('div', 'editor c-' + (n.color || 'default'));
    var head = el('header', 'ed-head');
    var back = el('button', 'icon', '←');
    back.title = '戻る';
    back.onclick = function () { close(); };
    head.appendChild(back);
    head.appendChild(el('span', 'grow'));
    var pin = el('button', 'icon', '📌');
    pin.title = '固定';
    pin.onclick = function () { n.pinned = !n.pinned; if (n.pinned) n.archived = false; paintHead(); changed(); };
    var archive = el('button', 'icon', '📦');
    archive.title = 'アーカイブ';
    archive.onclick = function () {
      n.archived = !n.archived;
      if (n.archived) n.pinned = false;
      changed();
      App.toast(n.archived ? 'アーカイブしました' : 'アーカイブから戻しました');
      close();
    };
    if (!readOnly) { head.appendChild(pin); head.appendChild(archive); }
    root.appendChild(head);

    var scroll = el('div', 'ed-scroll');
    var title = el('div', 'ed-title');
    title.contentEditable = readOnly ? 'false' : 'plaintext-only';
    title.dataset.placeholder = 'タイトル';
    title.textContent = n.title || '';
    title.addEventListener('input', changed);
    title.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); focusFirst(); }
    });
    var list = el('div', 'ed-blocks');
    var labels = el('div', 'ed-labels');
    var meta = el('div', 'ed-meta');
    scroll.appendChild(title);
    scroll.appendChild(list);
    scroll.appendChild(labels);
    scroll.appendChild(meta);
    root.appendChild(scroll);

    var rec = el('div', 'rec-bar');
    root.appendChild(rec);

    // Formatting bar: shown while a rich-text block has the caret.
    var fmt = el('div', 'fmt-bar');
    [['B', 'bold', '太字'], ['I', 'italic', '斜体'], ['U', 'underline', '下線'], ['S', 'strikeThrough', '取り消し線'], ['▮', 'mark', 'ハイライト'],
     ['H1', 'h1', '大見出し'], ['H2', 'h2', '見出し'], ['本文', 'p', '本文'], ['•', 'insertUnorderedList', '箇条書き'], ['1.', 'insertOrderedList', '番号付き'],
     ['❝', 'blockquote', '引用'], ['</>', 'pre', 'コード'], ['🔗', 'link', 'リンク'], ['✕', 'clear', '書式をクリア']].forEach(function (f) {
      var b = el('button', 'fmt', f[0]);
      b.title = f[2];
      b.addEventListener('pointerdown', function (e) { e.preventDefault(); });
      b.onclick = function () { format(f[1]); };
      fmt.appendChild(b);
    });
    root.appendChild(fmt);

    var foot = el('footer', 'ed-foot');
    function footBtn(icon, label, on) {
      var b = el('button', 'icon', icon);
      b.title = label;
      b.setAttribute('aria-label', label);
      b.onclick = on;
      foot.appendChild(b);
      return b;
    }
    if (readOnly) {
      footBtn('↩', '元に戻す', function () { n.trashed = 0; readOnly = false; changed(); flush(); App.toast('メモを元に戻しました'); close(); });
      footBtn('🗑', '完全に削除', function () { if (confirm('このメモを完全に削除しますか？')) { App.native('delete', { id: n.id }); destroy(); } });
    } else {
      footBtn('＋', '追加', function (e) { addMenu(e.currentTarget); });
      footBtn('🎨', '色', function (e) { App.colorPicker(e.currentTarget, n.color, function (c) { n.color = c; root.className = 'editor c-' + (c || 'default'); changed(); }); });
      footBtn('🏷', 'ラベル', function () { App.labelDialog([n], function (l) { n.labels = l; paintLabels(); changed(); }); });
      footBtn('🎤', '録音', function () { startRecording(); });
      footBtn('✎', '手書き', function () { insertInk(); });
    }
    var stamp = el('span', 'ed-stamp');
    foot.appendChild(stamp);
    footBtn('⋮', 'その他', function (e) { moreMenu(e.currentTarget); });
    root.appendChild(foot);
    document.body.appendChild(root);
    document.body.classList.add('editing');

    // ---- blocks -------------------------------------------------------------------------

    function changed() {
      saved = false;
      clearTimeout(saveTimer);
      saveTimer = setTimeout(flush, 700);
    }

    function sync() {
      $$('[data-bi]', list).forEach(function (d) {
        var b = blocks[+d.dataset.bi];
        if (b && b.t === 'text' && b.dirty) { b.md = toMarkdown(d.querySelector('.rt').innerHTML); b.dirty = false; }
      });
    }

    function body() {
      if (sourceMode) return list.querySelector('textarea').value.replace(/\s+$/, '');
      sync();
      return MD.serialize(blocks.filter(function (b) { return !(b.t === 'text' && !b.md.trim()) && !(b.t === 'list' && !b.items.length); }));
    }

    function empty() {
      return !String(title.textContent).trim() && !body().replace(/- \[ \]\s*/g, '').trim();
    }

    function flush() {
      clearTimeout(saveTimer);
      if (readOnly && n.trashed) return;
      if (saved) return;
      n.title = String(title.textContent || '').replace(/\n/g, ' ').trim();
      n.body = body();
      if (isNew && empty()) return; // a new note stays unsaved until it has something
      isNew = false;
      saved = true;
      App.saveNote(JSON.parse(JSON.stringify(n)));
      paintMeta();
    }

    function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }

    function draw() {
      if (sourceMode) return;
      sync();
      list.innerHTML = '';
      if (!blocks.length || blocks[blocks.length - 1].t !== 'text' && !readOnly) {
        if (!blocks.some(function (b) { return b.t === 'text'; }) || blocks[blocks.length - 1].t === 'file' || blocks[blocks.length - 1].t === 'table')
          blocks.push({ t: 'text', md: '' });
      }
      blocks.forEach(function (b, i) {
        var d = el('div', 'blk blk-' + b.t + (b.kind ? ' k-' + b.kind : ''));
        d.dataset.bi = i;
        if (b.t === 'text') textBlock(d, b, i);
        else if (b.t === 'list') listBlock(d, b, i);
        else if (b.t === 'table') tableBlock(d, b, i);
        else if (b.t === 'file') fileBlock(d, b, i);
        list.appendChild(d);
      });
    }

    function textBlock(d, b, i) {
      var rt = el('div', 'rt md');
      rt.innerHTML = MD.html(b.md);
      if (!readOnly) {
        rt.contentEditable = 'true';
        rt.dataset.placeholder = i === 0 || blocks.length === 1 ? 'メモを入力…' : '';
        rt.addEventListener('input', function () { b.dirty = true; changed(); });
        rt.addEventListener('focus', function () { focusIndex = i; root.classList.add('fmt-on'); });
        rt.addEventListener('blur', function () { setTimeout(function () { if (!root.contains(document.activeElement) || !document.activeElement.closest('.rt')) root.classList.remove('fmt-on'); }, 50); });
        rt.addEventListener('click', function (e) {
          var a = e.target.closest('a');
          if (a && a.href) { e.preventDefault(); App.native('openUrl', { url: a.href }); }
        });
        rt.addEventListener('paste', function (e) {
          // Plain text pastes as Markdown-ish text; rich pastes are cleaned.
          var t = e.clipboardData && e.clipboardData.getData('text/plain');
          var h = e.clipboardData && e.clipboardData.getData('text/html');
          if (h) { e.preventDefault(); document.execCommand('insertHTML', false, MD.sanitize(h)); }
          else if (t) { e.preventDefault(); document.execCommand('insertText', false, t); }
        });
      } else rt.addEventListener('click', function (e) { var a = e.target.closest('a'); if (a) { e.preventDefault(); App.native('openUrl', { url: a.href }); } });
      d.appendChild(rt);
    }

    function format(cmd) {
      var active = document.activeElement;
      if (!active || !active.classList.contains('rt')) return;
      if (/^(h1|h2|p|blockquote|pre)$/.test(cmd)) document.execCommand('formatBlock', false, cmd);
      else if (cmd === 'mark') {
        var s = window.getSelection();
        if (s && s.rangeCount && !s.isCollapsed) document.execCommand('insertHTML', false, '<mark>' + MD.esc(s.toString()) + '</mark>');
      } else if (cmd === 'link') {
        var url = prompt('リンク先のURL', 'https://');
        if (url && /^https?:\/\//.test(url)) {
          var sel = window.getSelection();
          if (sel && sel.isCollapsed) document.execCommand('insertHTML', false, '<a href="' + MD.esc(url) + '">' + MD.esc(url) + '</a>');
          else document.execCommand('createLink', false, url);
        }
      } else if (cmd === 'clear') { document.execCommand('removeFormat'); document.execCommand('formatBlock', false, 'p'); }
      else document.execCommand(cmd);
      var bd = active.closest('[data-bi]');
      if (bd) { blocks[+bd.dataset.bi].dirty = true; changed(); }
    }

    // Keep-style check list: unchecked on top, checked items folded below.
    function listBlock(d, b, i) {
      var openBox = el('div', 'items');
      var doneBox = el('div', 'items done');
      var done = b.items.filter(function (x) { return x.checked; }).length;
      b.items.forEach(function (it, k) {
        var row = el('div', 'item' + (it.checked ? ' checked' : ''));
        row.style.setProperty('--indent', it.indent || 0);
        var handle = el('span', 'drag', '⋮⋮');
        var cb = el('input');
        cb.type = 'checkbox';
        cb.checked = it.checked;
        cb.disabled = readOnly;
        cb.onchange = function () {
          it.checked = cb.checked;
          b.items.splice(k, 1);
          if (it.checked) b.items.push(it);
          else {
            var last = -1;
            b.items.forEach(function (x, j) { if (!x.checked) last = j; });
            b.items.splice(last + 1, 0, it);
          }
          changed();
          draw();
        };
        var tx = el('div', 'it-text');
        tx.textContent = it.text;
        if (!readOnly) {
          tx.contentEditable = 'plaintext-only';
          tx.addEventListener('focus', function () { focusIndex = i; });
          tx.addEventListener('input', function () { it.text = tx.textContent.replace(/\n/g, ' '); changed(); });
          tx.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
              e.preventDefault();
              var pos = caret(tx), rest = it.text.slice(pos);
              it.text = it.text.slice(0, pos);
              var nu = { text: rest, checked: false, indent: it.indent || 0 };
              b.items.splice(k + 1, 0, nu);
              changed();
              draw();
              focusItem(i, k + 1, 0);
            } else if (e.key === 'Backspace' && caret(tx) === 0 && !(window.getSelection() + '')) {
              e.preventDefault();
              if (it.indent) { it.indent--; changed(); draw(); focusItem(i, k, 0); return; }
              if (k === 0 && it.text) return;
              var prev = k > 0 ? b.items[k - 1] : null;
              b.items.splice(k, 1);
              if (prev) { var at = prev.text.length; prev.text += it.text; changed(); draw(); focusItem(i, k - 1, at); }
              else { changed(); draw(); }
            } else if (e.key === 'Tab') {
              e.preventDefault();
              it.indent = Math.max(0, Math.min(3, (it.indent || 0) + (e.shiftKey ? -1 : 1)));
              changed();
              draw();
              focusItem(i, k, caret(tx));
            }
          });
        }
        var del = el('button', 'it-del', '✕');
        del.title = '削除';
        del.onclick = function () { b.items.splice(k, 1); changed(); draw(); };
        if (!readOnly) row.appendChild(handle);
        row.appendChild(cb);
        row.appendChild(tx);
        if (!readOnly) row.appendChild(del);
        (it.checked ? doneBox : openBox).appendChild(row);
        if (!readOnly && !it.checked) dragSort(handle, row, b, k, openBox);
        if (!readOnly) swipeIndent(row, it);
      });
      d.appendChild(openBox);
      if (!readOnly) {
        var add = el('button', 'it-add', '＋ リストアイテム');
        add.onclick = function () {
          var last = -1;
          b.items.forEach(function (x, j) { if (!x.checked) last = j; });
          b.items.splice(last + 1, 0, { text: '', checked: false, indent: 0 });
          changed();
          draw();
          focusItem(i, last + 1, 0);
        };
        d.appendChild(add);
      }
      if (done) {
        var h = el('button', 'done-head' + (b.fold ? ' folded' : ''), (b.fold ? '▸ ' : '▾ ') + 'チェック済みのアイテム ' + done + ' 件');
        h.onclick = function () { b.fold = !b.fold; draw(); };
        d.appendChild(h);
        if (!b.fold) d.appendChild(doneBox);
      }
    }

    // Swipe an item right / left to indent it (as in Keep).
    function swipeIndent(row, it) {
      var x0 = null, y0 = 0;
      row.addEventListener('touchstart', function (e) { if (e.touches.length === 1) { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; } }, { passive: true });
      row.addEventListener('touchend', function (e) {
        if (x0 == null) return;
        var dx = e.changedTouches[0].clientX - x0, dy = e.changedTouches[0].clientY - y0;
        x0 = null;
        if (Math.abs(dx) > 60 && Math.abs(dy) < 30) {
          it.indent = Math.max(0, Math.min(3, (it.indent || 0) + (dx > 0 ? 1 : -1)));
          changed();
          draw();
        }
      });
    }

    function dragSort(handle, row, b, k, box) {
      handle.addEventListener('pointerdown', function (e) {
        e.preventDefault();
        handle.setPointerCapture(e.pointerId);
        row.classList.add('dragging');
        var rows = Array.prototype.slice.call(box.children);
        var target = k;
        function move(ev) {
          var y = ev.clientY;
          target = 0;
          rows.forEach(function (r, j) { var rc = r.getBoundingClientRect(); if (y > rc.top + rc.height / 2) target = j; });
          rows.forEach(function (r, j) { r.classList.toggle('drop-after', j === target && j !== k); });
        }
        function up() {
          handle.removeEventListener('pointermove', move);
          handle.removeEventListener('pointerup', up);
          handle.removeEventListener('pointercancel', up);
          row.classList.remove('dragging');
          if (target !== k) {
            var it = b.items.splice(k, 1)[0];
            b.items.splice(target, 0, it);
            changed();
          }
          draw();
        }
        handle.addEventListener('pointermove', move);
        handle.addEventListener('pointerup', up);
        handle.addEventListener('pointercancel', up);
      });
    }

    function caret(node) {
      var s = window.getSelection();
      if (!s.rangeCount) return 0;
      var r = s.getRangeAt(0).cloneRange();
      r.selectNodeContents(node);
      r.setEnd(s.getRangeAt(0).endContainer, s.getRangeAt(0).endOffset);
      return r.toString().length;
    }

    function placeCaret(node, at) {
      node.focus();
      var s = window.getSelection(), r = document.createRange();
      var t = node.firstChild;
      if (t && t.nodeType === 3) r.setStart(t, Math.min(at, t.length));
      else r.setStart(node, 0);
      r.collapse(true);
      s.removeAllRanges();
      s.addRange(r);
    }

    function focusItem(bi, k, at) {
      var d = list.querySelector('[data-bi="' + bi + '"]');
      if (!d) return;
      var b = blocks[bi], it = b.items[k];
      if (!it) return;
      var rows = $$('.items:not(.done) .item', d);
      var openIndex = b.items.filter(function (x) { return !x.checked; }).indexOf(it);
      var row = rows[openIndex];
      if (row) placeCaret(row.querySelector('.it-text'), at);
    }

    function tableBlock(d, b, i) {
      var wrap = el('div', 'tbl-wrap');
      var t = el('table', 'tbl');
      b.rows.forEach(function (r, ri) {
        var tr = el('tr');
        r.forEach(function (c, ci) {
          var cell = el(ri ? 'td' : 'th');
          cell.textContent = c;
          if (b.align[ci]) cell.style.textAlign = b.align[ci];
          if (!readOnly) {
            cell.contentEditable = 'plaintext-only';
            cell.addEventListener('focus', function () { focusIndex = i; d.classList.add('active'); d.dataset.r = ri; d.dataset.c = ci; });
            cell.addEventListener('input', function () { b.rows[ri][ci] = cell.textContent.replace(/\n/g, ' '); changed(); });
            cell.addEventListener('keydown', function (e) {
              if (e.key === 'Enter') {
                e.preventDefault();
                if (ri === b.rows.length - 1) { b.rows.push(b.rows[0].map(function () { return ''; })); changed(); draw(); }
                var nd = list.querySelector('[data-bi="' + i + '"]');
                var next = nd && nd.querySelectorAll('tr')[ri + 1];
                if (next) next.children[ci].focus();
              }
            });
          }
          tr.appendChild(cell);
        });
        t.appendChild(tr);
      });
      wrap.appendChild(t);
      d.appendChild(wrap);
      if (readOnly) return;
      var tools = el('div', 'tbl-tools');
      [['＋行', function () { b.rows.push(b.rows[0].map(function () { return ''; })); }],
       ['＋列', function () { b.rows.forEach(function (r, ri) { r.push(ri ? '' : '列' + (r.length + 1)); }); b.align.push(''); }],
       ['−行', function () { var r = +d.dataset.r || b.rows.length - 1; if (b.rows.length > 2 && r > 0) b.rows.splice(r, 1); }],
       ['−列', function () { var c = d.dataset.c != null ? +d.dataset.c : b.rows[0].length - 1; if (b.rows[0].length > 1) { b.rows.forEach(function (r) { r.splice(c, 1); }); b.align.splice(c, 1); } }],
       ['表を削除', function () { if (confirm('表を削除しますか？')) blocks.splice(i, 1); }]].forEach(function (x) {
        var btn = el('button', '', x[0]);
        btn.addEventListener('pointerdown', function (e) { e.preventDefault(); });
        btn.onclick = function () { x[1](); changed(); draw(); };
        tools.appendChild(btn);
      });
      d.appendChild(tools);
    }

    function fileBlock(d, b, i) {
      var src = 'media/' + b.name;
      var menuBtn = el('button', 'blk-menu', '⋯');
      menuBtn.title = 'メニュー';
      menuBtn.onclick = function (e) { e.stopPropagation(); fileMenu(menuBtn, b, i); };
      if (b.kind === 'image' || b.kind === 'ink') {
        var fig = el('figure');
        var img = el('img');
        img.src = src;
        img.alt = b.label || '';
        img.loading = 'lazy';
        img.onerror = function () { fig.classList.add('missing'); fig.dataset.msg = '同期されると表示されます'; img.remove(); };
        fig.appendChild(img);
        fig.onclick = function () { if (b.kind === 'ink' && !readOnly) editInk(b); else viewer(b); };
        d.appendChild(fig);
        if (b.kind === 'ink' && b.quote) d.appendChild(quoteBox(b));
      } else if (b.kind === 'audio') {
        var a = el('div', 'audio');
        var player = el('audio');
        player.controls = true;
        player.preload = 'metadata';
        player.src = src;
        a.appendChild(player);
        d.appendChild(a);
        if (Transcribing[b.name]) d.appendChild(el('div', 'quote busy', '文字にしています…'));
        else if (b.quote) d.appendChild(quoteBox(b));
      } else if (b.kind === 'video') {
        var v = el('video');
        v.controls = true;
        v.preload = 'metadata';
        v.src = src;
        d.appendChild(v);
      } else {
        var c = el('div', 'file-card');
        var th = el('div', 'file-thumb');
        if (b.kind === 'pdf') {
          var ti = el('img');
          ti.src = 'thumb/' + b.name;
          ti.onerror = function () { ti.remove(); th.textContent = 'PDF'; };
          th.appendChild(ti);
        } else th.textContent = (b.name.split('.').pop() || 'FILE').toUpperCase().slice(0, 4);
        c.appendChild(th);
        var info = el('div', 'file-info');
        info.appendChild(el('b', '', b.label || b.name));
        info.appendChild(el('span', '', b.kind === 'pdf' ? 'PDF・タップで開く' : 'タップで開く'));
        c.appendChild(info);
        c.onclick = function () { App.native('openFile', { name: b.name }); };
        d.appendChild(c);
      }
      d.appendChild(menuBtn);
    }

    function quoteBox(b) {
      var q = el('div', 'quote');
      q.textContent = b.quote;
      if (!readOnly) {
        q.contentEditable = 'plaintext-only';
        q.addEventListener('input', function () { b.quote = q.textContent; changed(); });
      }
      return q;
    }

    function fileMenu(anchor, b, i) {
      var items = [];
      if (b.kind === 'ink' && !readOnly) items.push(['✎ 手書きを編集', function () { editInk(b); }]);
      if (b.kind === 'ink' || b.kind === 'image') items.push(['🔤 文字にする（Gemini）', function () { App.native('inkText', { note: n.id, name: b.name }); }]);
      if (b.kind === 'audio') items.push(['🔤 もう一度文字にする', function () { flush(); Transcribing[b.name] = true; App.native('transcribe', { note: n.id, name: b.name }); draw(); }]);
      items.push(['↗ 開く', function () { App.native('openFile', { name: b.name }); }]);
      items.push(['⤴ 共有', function () { App.native('shareFile', { name: b.name }); }]);
      if (!readOnly) {
        if (i > 0) items.push(['↑ 上へ', function () { var x = blocks.splice(i, 1)[0]; blocks.splice(i - 1, 0, x); changed(); draw(); }]);
        if (i < blocks.length - 1) items.push(['↓ 下へ', function () { var x = blocks.splice(i, 1)[0]; blocks.splice(i + 1, 0, x); changed(); draw(); }]);
        items.push(null);
        items.push(['削除', function () { blocks.splice(i, 1); changed(); draw(); }, true]);
      }
      App.menu(anchor, items);
    }

    function viewer(b) {
      var v = el('div', 'viewer');
      var img = el('img');
      img.src = 'media/' + b.name;
      v.appendChild(img);
      var bar = el('div', 'viewer-bar');
      var x = el('button', 'icon', '✕');
      var open = el('button', '', '他のアプリで開く');
      open.onclick = function () { App.native('openFile', { name: b.name }); };
      bar.appendChild(x);
      bar.appendChild(open);
      v.appendChild(bar);
      var close = App.sheet('', v, null, {});
      x.onclick = close;
      img.onclick = close;
    }

    // ---- inserting ---------------------------------------------------------------------------

    function insertAt() {
      return focusIndex >= 0 && focusIndex < blocks.length ? focusIndex + 1 : blocks.length;
    }

    function insert(b) {
      sync();
      var at = insertAt();
      // Into an empty text block: replace it instead of leaving a gap.
      if (blocks[at - 1] && blocks[at - 1].t === 'text' && !blocks[at - 1].md.trim()) at--, blocks.splice(at, 1);
      else if (at === blocks.length && blocks.length && blocks[blocks.length - 1].t === 'text' && !blocks[blocks.length - 1].md.trim()) at = blocks.length - 1, blocks.pop();
      blocks.splice(at, 0, b);
      focusIndex = at;
      changed();
      draw();
      return at;
    }

    function addMenu(anchor) {
      App.menu(anchor, [
        ['📷 写真を撮る', function () { App.native('camera', { note: n.id }); }],
        ['🖼 画像・ファイルを追加', function () { App.native('attach', { note: n.id }); }],
        ['✎ 手書き', insertInk],
        ['🎤 録音', startRecording],
        ['☑ チェックボックス', function () { var at = insert({ t: 'list', items: [{ text: '', checked: false, indent: 0 }] }); focusItem(at, 0, 0); }],
        ['▦ 表', function () { insert({ t: 'table', rows: [['項目', '内容'], ['', '']], align: ['', ''] }); }],
        ['¶ テキスト', function () { var at = insert({ t: 'text', md: '' }); var d = list.querySelector('[data-bi="' + at + '"] .rt'); if (d) d.focus(); }]
      ]);
    }

    function insertInk() {
      Ink.open({
        onDone: function (png, strokes) {
          var req = App.newId();
          pendingInk[req] = { replace: null };
          App.native('saveInk', { note: n.id, req: req, png: png, strokes: strokes });
        }
      });
    }

    function editInk(b) {
      var req = App.newId();
      pendingStrokes[req] = b;
      App.native('inkStrokes', { name: b.name, req: req });
    }

    var pendingInk = {}, pendingStrokes = {};

    function startRecording() {
      if (App.state.recording) { App.native('stopRecord'); return; }
      flush();
      if (isNew && empty()) { isNew = false; saved = false; n.body = ''; App.saveNote(JSON.parse(JSON.stringify(n))); saved = true; }
      App.native('record', { note: n.id });
    }

    function paintRec() {
      if (!recording) { rec.classList.remove('on'); rec.innerHTML = ''; return; }
      rec.classList.add('on');
      if (!rec.firstChild) {
        rec.appendChild(el('span', 'dot'));
        rec.appendChild(el('span', 'time', '0:00'));
        rec.appendChild(el('span', 'meter')).appendChild(el('i'));
        var stop = el('button', 'stop', '■ 停止');
        stop.onclick = function () { App.native('stopRecord'); };
        rec.appendChild(stop);
      }
      rec.querySelector('.time').textContent = '録音中 ' + fmtDuration((Date.now() - recording.start) / 1000);
    }

    // ---- menus ------------------------------------------------------------------------------

    function moreMenu(anchor) {
      var items = [];
      if (!readOnly) {
        items.push(['🗑 削除', function () {
          n.trashed = Date.now();
          n.pinned = false;
          saved = false;
          if (isNew && empty()) { destroy(); return; }
          flush();
          App.toast('メモをゴミ箱に移動しました');
          destroy();
        }, true]);
        items.push(['⧉ コピーを作成', function () {
          flush();
          var c = JSON.parse(JSON.stringify(n));
          c.id = App.newId();
          c.created = Date.now();
          delete c.importKey;
          App.saveNote(c);
          App.toast('コピーを作成しました');
        }]);
      }
      items.push(['⤴ 送る', function () { flush(); App.native('shareNote', { id: n.id }); }]);
      items.push(['📋 テキストをコピー', function () { flush(); App.native('copyText', { text: (n.title ? n.title + '\n\n' : '') + MD.plain(n.body) }); }]);
      if (!readOnly) {
        var hasList = blocks.some(function (b) { return b.t === 'list'; });
        items.push([hasList ? '☐ チェックボックスを非表示' : '☑ チェックボックスを表示', function () { toggleChecks(hasList); }]);
        items.push([sourceMode ? '👁 通常の表示に戻す' : '⌨ Markdownで編集', toggleSource]);
      }
      if (n.source) items.push(['🔗 元のページを開く', function () { App.native('openUrl', { url: n.source }); }]);
      App.menu(anchor, items);
    }

    function toggleChecks(hasList) {
      sync();
      var out = [];
      blocks.forEach(function (b) {
        if (hasList && b.t === 'list') out.push({ t: 'text', md: b.items.map(function (x) { return x.text; }).join('\n') });
        else if (!hasList && b.t === 'text' && b.md.trim()) {
          out.push({ t: 'list', items: MD.plain(b.md).split('\n').filter(function (x) { return x.trim(); }).map(function (x) { return { text: x.trim(), checked: false, indent: 0 }; }) });
        } else out.push(b);
      });
      if (!hasList && !out.some(function (b) { return b.t === 'list'; })) out.push({ t: 'list', items: [{ text: '', checked: false, indent: 0 }] });
      blocks = out;
      changed();
      draw();
    }

    function toggleSource() {
      if (!sourceMode) {
        var md = body();
        sourceMode = true;
        list.innerHTML = '';
        var ta = el('textarea', 'source');
        ta.value = md;
        ta.addEventListener('input', changed);
        list.appendChild(ta);
        root.classList.add('source');
        setTimeout(function () { ta.style.height = Math.max(240, ta.scrollHeight + 20) + 'px'; }, 0);
      } else {
        var v = list.querySelector('textarea').value;
        sourceMode = false;
        root.classList.remove('source');
        blocks = MD.parse(v);
        changed();
        draw();
      }
    }

    // ---- paint -----------------------------------------------------------------------------

    function paintHead() {
      pin.classList.toggle('on', !!n.pinned);
      pin.title = n.pinned ? '固定を解除' : '固定';
      archive.title = n.archived ? 'アーカイブから戻す' : 'アーカイブ';
    }

    function paintLabels() {
      labels.innerHTML = '';
      (n.labels || []).forEach(function (l) {
        var c = el('button', 'chip', l);
        c.onclick = function () { if (!readOnly) App.labelDialog([n], function (x) { n.labels = x; paintLabels(); changed(); }); };
        labels.appendChild(c);
      });
    }

    function paintMeta() {
      stamp.textContent = isNew ? '' : '編集：' + App.when(n.updated || Date.now());
      meta.innerHTML = '';
      if (n.source) {
        var a = el('button', 'link', '🔗 ' + (App.domain(n.source) || n.source));
        a.onclick = function () { App.native('openUrl', { url: n.source }); };
        meta.appendChild(a);
      }
      if (readOnly) meta.appendChild(el('p', 'muted', 'ゴミ箱のメモは編集できません。「元に戻す」で戻せます。'));
    }

    function focusFirst() {
      var first = list.querySelector('.rt, .it-text, td, th');
      if (first) placeCaret(first, (first.textContent || '').length);
    }

    function destroy() {
      clearTimeout(saveTimer);
      clearInterval(levelTimer);
      root.remove();
      document.body.classList.remove('editing');
      App.native('editing', { id: '' });
      Editor.current = null;
    }

    function close() {
      if (window.Ink && Ink.current) Ink.current.close();
      flush();
      if (isNew && empty()) App.toast('空のメモは保存しませんでした');
      destroy();
    }

    // ---- events from the platform -------------------------------------------------------------

    var levelTimer = null;
    var api = {
      note: n,
      close: close,
      flush: flush,
      // The stored note changed elsewhere (sync, widget): take it if we have nothing unsaved.
      external: function (fresh) {
        if (!fresh || !saved || sourceMode) return;
        if (fresh.body === n.body && fresh.title === n.title && fresh.color === n.color) return;
        if (document.activeElement && root.contains(document.activeElement) && document.activeElement !== document.body) return;
        n = JSON.parse(JSON.stringify(fresh));
        api.note = n;
        blocks = MD.parse(n.body);
        title.textContent = n.title || '';
        root.className = 'editor c-' + (n.color || 'default');
        draw();
        paintHead();
        paintLabels();
        paintMeta();
      },
      attached: function (d) {
        (d.files || []).forEach(function (f) {
          insert({ t: 'file', name: f.name, label: /^image\//.test(f.mime) ? '' : f.label, kind: MD.kindOf(f.name) });
        });
        flush();
      },
      inkSaved: function (d) {
        var p = pendingInk[d.req];
        delete pendingInk[d.req];
        if (p && p.replace) { p.replace.name = d.name; changed(); draw(); }
        else insert({ t: 'file', name: d.name, label: '', kind: 'ink', quote: '' });
        flush();
      },
      inkStrokes: function (d) {
        var b = pendingStrokes[d.req];
        delete pendingStrokes[d.req];
        if (!b) return;
        Ink.open({
          strokes: d.strokes,
          onDone: function (png, strokes) {
            var req = App.newId();
            pendingInk[req] = { replace: b };
            App.native('saveInk', { note: n.id, req: req, png: png, strokes: strokes });
          },
          onEmpty: function () { blocks.splice(blocks.indexOf(b), 1); changed(); draw(); }
        });
      },
      recording: function (d) {
        if (d && d.note === n.id) {
          recording = { start: d.start || Date.now() };
          clearInterval(levelTimer);
          levelTimer = setInterval(function () { paintRec(); App.native('level'); }, 250);
        } else { recording = null; clearInterval(levelTimer); }
        paintRec();
      },
      level: function (v) {
        var m = rec.querySelector('.meter i');
        if (m) m.style.width = Math.max(4, v) + '%';
      },
      recorded: function (d) {
        recording = null;
        clearInterval(levelTimer);
        paintRec();
        Transcribing[d.name] = App.state.transcribe !== 'off';
        insert({ t: 'file', name: d.name, label: '', kind: 'audio', quote: '' });
        flush();
      },
      transcript: function (d) {
        delete Transcribing[d.name];
        var b = blocks.filter(function (x) { return x.t === 'file' && x.name === d.name; })[0];
        if (b) b.quote = d.text;
        else blocks.push({ t: 'file', name: d.name, kind: MD.kindOf(d.name), label: '', quote: d.text });
        changed();
        draw();
        flush();
        App.toast('文字にしました');
      },
      toggle: function (d) {
        sync();
        var lines = body().split('\n');
        var l = lines[d.line];
        if (l != null) {
          lines[d.line] = l.replace(/^(\s*[-*+]\s+\[)([ xX])\]/, function (m, a, c) { return a + (c === ' ' ? 'x' : ' ') + ']'; });
          blocks = MD.parse(lines.join('\n'));
          changed();
          draw();
        }
      }
    };
    Editor.current = api;

    draw();
    paintHead();
    paintLabels();
    paintMeta();
    if (App.state.recording === n.id) api.recording({ note: n.id, start: App.state.recordingStart });

    var kind = opts.kind;
    setTimeout(function () {
      if (kind === 'ink') insertInk();
      else if (kind === 'audio') startRecording();
      else if (kind === 'photo') App.native('camera', { note: n.id });
      else if (kind === 'file') App.native('attach', { note: n.id });
      else if (kind === 'list' && !root.contains(document.activeElement)) focusItem(0, 0, 0);
      else if (isNew && !root.contains(document.activeElement)) { var rt = list.querySelector('.rt'); if (rt) rt.focus(); }
    }, 60);
  };

  var Transcribing = {};
  App.on('attached', function (d) { if (Editor.current && Editor.current.note.id === d.note) Editor.current.attached(d); else if (App.byId[d.note]) attachClosed(d); });
  App.on('inkSaved', function (d) { if (Editor.current && Editor.current.note.id === d.note) Editor.current.inkSaved(d); });
  App.on('inkStrokes', function (d) { if (Editor.current) Editor.current.inkStrokes(d); });
  App.on('recording', function (d) { App.state.recording = d && d.note || ''; if (Editor.current) Editor.current.recording(d); });
  App.on('level', function (v) { if (Editor.current) Editor.current.level(v); });
  App.on('recorded', function (d) { App.state.recording = ''; if (Editor.current && Editor.current.note.id === d.note) Editor.current.recorded(d); });
  App.on('transcript', function (d) { if (Editor.current && Editor.current.note.id === d.note) Editor.current.transcript(d); });
  App.on('transcribing', function (name) { if (!name) Transcribing = {}; });
  App.on('toggle', function (d) { if (Editor.current && Editor.current.note.id === d.note) Editor.current.toggle(d); });

  // A file chosen after the editor was closed still lands in its note.
  function attachClosed(d) {
    var n = JSON.parse(JSON.stringify(App.byId[d.note]));
    var add = (d.files || []).map(function (f) {
      return MD.serializeBlock({ t: 'file', name: f.name, label: /^image\//.test(f.mime) ? '' : f.label, kind: MD.kindOf(f.name) });
    }).join('\n\n');
    n.body = n.body ? n.body + '\n\n' + add : add;
    App.saveNote(n);
  }
})();
