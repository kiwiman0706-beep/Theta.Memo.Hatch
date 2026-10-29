'use strict';
// Note bodies (Obsidian Markdown) ⇄ editor blocks, and Markdown → safe HTML for cards.
// Shared by every platform's UI; pure functions (also loaded by the Node tests).
(function (root) {
  var FILES = 'Memo/_files/';
  var EMBED_LINE = /^\s*!\[\[([^\]|#]+)(?:[|#]([^\]]*))?\]\]\s*$/;
  var MD_IMG_LINE = /^\s*!\[([^\]]*)\]\(<?([^)>\s]+)>?\)\s*$/;
  var TASK = /^(\s*)[-*+]\s+\[([ xX])\]\s?(.*)$/;
  var TABLE_ROW = /^\s*\|.*\|\s*$/;
  var TABLE_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

  function baseName(p) {
    p = String(p || '').trim().replace(/\\/g, '/');
    try { p = decodeURIComponent(p); } catch (e) {}
    return p.slice(p.lastIndexOf('/') + 1);
  }
  function safeName(n) { return /^[A-Za-z0-9_.-]+$/.test(n) && n.indexOf('..') < 0 && n[0] !== '.'; }

  function kindOf(name) {
    var n = String(name).toLowerCase();
    if (/\.ink\.png$/.test(n)) return 'ink';
    if (/\.(jpe?g|png|gif|webp|bmp|svg|heic|heif)$/.test(n)) return 'image';
    if (/\.(m4a|mp3|wav|ogg|opus|aac|3gp|amr|flac|weba)$/.test(n)) return 'audio';
    if (/\.(mp4|mov|webm|mkv)$/.test(n)) return 'video';
    if (/\.pdf$/.test(n)) return 'pdf';
    return 'file';
  }

  // An attachment line we manage: ![[Memo/_files/x.png]] (or Markdown image syntax).
  function fileLine(line) {
    var m = EMBED_LINE.exec(line), path, label = '';
    if (m) { path = m[1]; label = m[2] || ''; }
    else {
      m = MD_IMG_LINE.exec(line);
      if (!m) return null;
      path = m[2]; label = m[1] || '';
    }
    var dir = path.replace(/\\/g, '/');
    dir = dir.indexOf('/') >= 0 ? dir.slice(0, dir.lastIndexOf('/') + 1) : '';
    if (dir && !/_files\/$/.test(dir)) return null;
    var name = baseName(path);
    if (!safeName(name)) return null;
    if (/^\d+(x\d+)?$/.test(label)) label = ''; // Obsidian size, not a name
    return { t: 'file', name: name, label: label, kind: kindOf(name) };
  }

  function splitRow(line) {
    var s = line.trim();
    if (s[0] === '|') s = s.slice(1);
    if (s[s.length - 1] === '|' && s[s.length - 2] !== '\\') s = s.slice(0, -1);
    var cells = [], cur = '';
    for (var i = 0; i < s.length; i++) {
      if (s[i] === '\\' && s[i + 1] === '|') { cur += '\\|'; i++; continue; }
      if (s[i] === '|') { cells.push(cur.trim()); cur = ''; continue; }
      cur += s[i];
    }
    cells.push(cur.trim());
    return cells;
  }

  function parse(body) {
    var lines = String(body || '').replace(/\r\n/g, '\n').split('\n');
    var blocks = [], text = [];
    function flushText() {
      while (text.length && !text[0].trim()) text.shift();
      while (text.length && !text[text.length - 1].trim()) text.pop();
      if (text.length) blocks.push({ t: 'text', md: text.join('\n') });
      text = [];
    }
    var fence = null;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (fence) {
        text.push(l);
        if (l.trim().indexOf(fence) === 0) fence = null;
        continue;
      }
      var f = /^\s*(```|~~~)/.exec(l);
      if (f) { fence = f[1]; text.push(l); continue; }
      var file = fileLine(l);
      if (file) {
        flushText();
        if (file.kind === 'audio' || file.kind === 'ink') {
          var q = [];
          while (i + 1 < lines.length && /^>/.test(lines[i + 1])) { q.push(lines[i + 1].replace(/^>\s?/, '')); i++; }
          file.quote = q.join('\n').trim();
        }
        blocks.push(file);
        continue;
      }
      if (TASK.test(l)) {
        flushText();
        var items = [];
        while (i < lines.length && TASK.test(lines[i])) {
          var m = TASK.exec(lines[i]);
          var it = task(m[3]);
          it.checked = m[2] !== ' ';
          it.indent = Math.min(6, Math.floor(m[1].replace(/\t/g, '    ').length / 2));
          items.push(it);
          i++;
        }
        i--;
        blocks.push({ t: 'list', items: items });
        continue;
      }
      if (TABLE_ROW.test(l) && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1])) {
        flushText();
        var rows = [splitRow(l)], align = splitRow(lines[i + 1]).map(function (c) {
          return /^:-+:$/.test(c) ? 'center' : /-+:$/.test(c) ? 'right' : /^:-+/.test(c) ? 'left' : '';
        });
        i += 2;
        while (i < lines.length && TABLE_ROW.test(lines[i])) { rows.push(splitRow(lines[i])); i++; }
        i--;
        var w = 0;
        rows.forEach(function (r) { w = Math.max(w, r.length); });
        rows.forEach(function (r) { while (r.length < w) r.push(''); });
        while (align.length < w) align.push('');
        blocks.push({ t: 'table', rows: rows, align: align.slice(0, w) });
        continue;
      }
      text.push(l);
    }
    flushText();
    return blocks;
  }

  // Task details on a check list item, as the Obsidian Tasks plugin writes them:
  // "電話する #重要 #緊急 📅 2026-11-01 ✅ 2026-10-30". Other text and tags are left alone.
  var DUE = /\s*📅\s*(\d{4}-\d{2}-\d{2})/, DONE = /\s*✅\s*(\d{4}-\d{2}-\d{2})/, IMP = /\s*#重要(?=\s|$)/, URG = /\s*#緊急(?=\s|$)/;
  function task(raw) {
    var t = String(raw || ''), it = { text: '', due: '', doneDate: '', imp: false, urg: false };
    var m = DUE.exec(t); if (m) { it.due = m[1]; t = t.replace(DUE, ''); }
    m = DONE.exec(t); if (m) { it.doneDate = m[1]; t = t.replace(DONE, ''); }
    if (IMP.test(t)) { it.imp = true; t = t.replace(IMP, ''); }
    if (URG.test(t)) { it.urg = true; t = t.replace(URG, ''); }
    it.text = t.replace(/\s+$/, '');
    return it;
  }
  function taskText(it) {
    var t = String(it.text || '').replace(/\n/g, ' ').replace(/\s+$/, '');
    if (it.imp) t += ' #重要';
    if (it.urg) t += ' #緊急';
    if (it.due) t += ' 📅 ' + it.due;
    if (it.doneDate) t += ' ✅ ' + it.doneDate;
    return t;
  }

  // ---- outline tree (mind map) --------------------------------------------------------
  // Headings, list items (nested by indent), paragraphs and other blocks as one tree, and back.
  // A body written as headings and lists comes back unchanged; everything else is kept verbatim.
  var LI = /^(\s*)([-*+]|\d+[.)])\s+(\[([ xX])\]\s+)?(.*)$/, H = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
  function outline(body, title) {
    var root = { kind: 'root', text: title || '', children: [] };
    var lines = String(body || '').replace(/\r\n/g, '\n').split('\n');
    var hs = [{ node: root, level: 0 }], ls = [], para = null, raw = null, fence = null;
    function top() { return hs[hs.length - 1].node; }
    function end() { para = null; raw = null; }
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (fence) { raw.lines.push(l); if (l.trim().indexOf(fence) === 0) { fence = null; end(); } continue; }
      var f = /^\s*(```|~~~)/.exec(l);
      if (f) { fence = f[1]; raw = { kind: 'raw', lines: [l], children: [] }; top().children.push(raw); ls = []; para = null; continue; }
      if (!l.trim()) { end(); continue; }
      var m = H.exec(l);
      if (m) {
        end(); ls = [];
        var level = m[1].length;
        while (hs.length > 1 && hs[hs.length - 1].level >= level) hs.pop();
        var h = { kind: 'h', level: level, text: m[2], children: [] };
        top().children.push(h);
        hs.push({ node: h, level: level });
        continue;
      }
      m = LI.exec(l);
      if (m) {
        end();
        var ind = m[1].replace(/\t/g, '    ').length;
        while (ls.length && ls[ls.length - 1].indent >= ind) ls.pop();
        var li = { kind: 'li', marker: /^\d/.test(m[2]) ? m[2] : '-', task: !!m[3], checked: !!m[4] && m[4] !== ' ', text: m[5], children: [] };
        (ls.length ? ls[ls.length - 1].node : top()).children.push(li);
        ls.push({ node: li, indent: ind });
        continue;
      }
      if (fileLine(l) || TABLE_ROW.test(l)) {
        para = null;
        if (!raw || fileLine(l)) { raw = { kind: 'raw', lines: [], children: [] }; top().children.push(raw); }
        raw.lines.push(l);
        ls = [];
        if (fileLine(l)) raw = null;
        continue;
      }
      if (/^\s*>/.test(l) && raw && raw.lines.length && fileLine(raw.lines[0])) { raw.lines.push(l); continue; }
      raw = null;
      if (para) { para.lines.push(l); continue; }
      ls = [];
      para = { kind: 'p', lines: [l], children: [] };
      top().children.push(para);
    }
    return root;
  }

  function outlineBody(root) {
    var out = [];
    function blank() { if (out.length && out[out.length - 1] !== '') out.push(''); }
    function emit(node, depth) {
      node.children.forEach(function (c) {
        if (c.kind === 'h') {
          blank();
          out.push(new Array(Math.min(6, c.level || 2) + 1).join('#') + ' ' + String(c.text || '').replace(/\n/g, ' '));
          blank();
          emit(c, 0);
        } else if (c.kind === 'li') {
          if (depth === 0 && out.length && out[out.length - 1] !== '' && !/^\s*([-*+]|\d+[.)])\s/.test(out[out.length - 1])) blank();
          out.push(new Array(depth + 1).join('  ') + (c.marker || '-') + ' ' + (c.task ? '[' + (c.checked ? 'x' : ' ') + '] ' : '') + String(c.text || '').replace(/\n/g, ' '));
          emit(c, depth + 1);
        } else {
          blank();
          (c.lines || []).forEach(function (x) { out.push(x); });
          blank();
          emit(c, 0);
        }
      });
    }
    emit(root, 0);
    while (out.length && out[out.length - 1] === '') out.pop();
    while (out.length && out[0] === '') out.shift();
    return out.join('\n');
  }

  function cell(s) { return String(s == null ? '' : s).replace(/\n/g, ' ').replace(/(^|[^\\])\|/g, '$1\\|').trim(); }

  function serializeBlock(b) {
    if (b.t === 'text') return String(b.md || '').replace(/\s+$/, '');
    if (b.t === 'list') {
      return b.items.map(function (it) {
        return new Array((it.indent || 0) + 1).join('  ') + '- [' + (it.checked ? 'x' : ' ') + '] ' + taskText(it);
      }).join('\n');
    }
    if (b.t === 'table') {
      var w = b.rows[0] ? b.rows[0].length : 1;
      var out = ['| ' + b.rows[0].map(cell).join(' | ') + ' |'];
      var sep = [];
      for (var k = 0; k < w; k++) {
        var a = (b.align || [])[k];
        sep.push(a === 'center' ? ':---:' : a === 'right' ? '---:' : a === 'left' ? ':---' : '---');
      }
      out.push('| ' + sep.join(' | ') + ' |');
      for (var r = 1; r < b.rows.length; r++) out.push('| ' + b.rows[r].map(cell).join(' | ') + ' |');
      return out.join('\n');
    }
    if (b.t === 'file') {
      var label = b.label && b.kind !== 'image' && b.kind !== 'ink' ? '|' + String(b.label).replace(/[\[\]|\n]/g, ' ').trim() : '';
      var s = '![[' + FILES + b.name + label + ']]';
      if (b.quote && b.quote.trim()) s += '\n' + b.quote.trim().split('\n').map(function (q) { return q.trim() ? '> ' + q : '>'; }).join('\n');
      return s;
    }
    return '';
  }

  function serialize(blocks) {
    return blocks.map(serializeBlock).filter(function (s) { return s !== ''; }).join('\n\n');
  }

  // ---- reading a note at a glance -------------------------------------------------

  function plain(md) {
    return String(md || '')
      .replace(/!?\[\[([^\]|]*)(\|([^\]]*))?\]\]/g, function (m, p, x, alias) { return m[0] === '!' ? '' : (alias || baseName(p).replace(/\.md$/, '')); })
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/^\s*(#{1,6}|>|[-*+]\s+\[[ xX]\]|[-*+]|\d+\.)\s*/gm, '')
      .replace(/(\*\*|__|~~|==|`)/g, '')
      .replace(/<\/?u>/g, '');
  }

  function title(n) {
    var t = String(n.title || '').trim();
    if (t) return t;
    var lines = plain(n.body).split('\n');
    for (var i = 0; i < lines.length; i++) if (lines[i].trim() && !/^-{3,}$/.test(lines[i].trim()) && !/^\|/.test(lines[i])) return lines[i].trim();
    return '';
  }

  function summary(n) {
    var blocks = parse(n.body), s = { images: [], files: [], audio: [], ink: [], lists: 0, checked: 0, unchecked: 0, tables: 0, links: 0 };
    blocks.forEach(function (b) {
      if (b.t === 'file') {
        if (b.kind === 'image' || b.kind === 'video' || b.kind === 'pdf') s.images.push(b);
        if (b.kind === 'ink') { s.ink.push(b); s.images.push(b); }
        if (b.kind === 'audio') s.audio.push(b);
        if (b.kind === 'file' || b.kind === 'pdf') s.files.push(b);
      } else if (b.t === 'list') {
        s.lists++;
        b.items.forEach(function (it) { if (it.checked) s.checked++; else s.unchecked++; });
      } else if (b.t === 'table') s.tables++;
      else if (/https?:\/\//.test(b.md)) s.links++;
    });
    if (n.source) s.links++;
    return s;
  }

  // ---- HTML -----------------------------------------------------------------------

  var markedReady = false;
  function setupMarked() {
    if (markedReady || !root.marked) return;
    markedReady = true;
    root.marked.use({
      gfm: true,
      breaks: true,
      extensions: [
        {
          name: 'highlight', level: 'inline',
          start: function (src) { var i = src.indexOf('=='); return i < 0 ? undefined : i; },
          tokenizer: function (src) {
            var m = /^==(?=\S)([\s\S]*?\S)==/.exec(src);
            if (m) return { type: 'highlight', raw: m[0], tokens: this.lexer.inlineTokens(m[1]) };
          },
          renderer: function (t) { return '<mark>' + this.parser.parseInline(t.tokens) + '</mark>'; }
        },
        {
          name: 'wikilink', level: 'inline',
          start: function (src) { var i = src.indexOf('[['); return i < 0 ? undefined : (i > 0 && src[i - 1] === '!' ? i - 1 : i); },
          tokenizer: function (src) {
            var m = /^(!?)\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]*))?\]\]/.exec(src);
            if (m) return { type: 'wikilink', raw: m[0], embed: !!m[1], target: m[2], alias: m[3] || '' };
          },
          renderer: function (t) {
            var name = baseName(t.target);
            if (t.embed && safeName(name) && kindOf(name) === 'image') return '<img src="media/' + name + '" alt="">';
            return '<span class="wikilink">' + esc(t.alias || name.replace(/\.md$/, '')) + '</span>';
          }
        }
      ]
    });
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
  }

  var ALLOWED = /^(a|b|strong|i|em|u|s|del|mark|code|pre|p|br|hr|h[1-6]|ul|ol|li|blockquote|table|thead|tbody|tr|th|td|img|span|div|input|sup|sub|kbd)$/i;

  // Removes anything active from rendered Markdown (notes may come from other tools or the web).
  function sanitize(html) {
    if (typeof document === 'undefined') return html;
    var t = document.createElement('template');
    t.innerHTML = html;
    (function walk(node) {
      Array.prototype.slice.call(node.childNodes).forEach(function (c) {
        if (c.nodeType === 8) { c.remove(); return; }
        if (c.nodeType !== 1) return;
        if (!ALLOWED.test(c.tagName)) {
          if (/^(script|style|iframe|object|embed|link|meta|form|svg|math|template|video|audio)$/i.test(c.tagName)) c.remove();
          else { walk(c); while (c.firstChild) c.parentNode.insertBefore(c.firstChild, c); c.remove(); }
          return;
        }
        Array.prototype.slice.call(c.attributes).forEach(function (a) {
          var n = a.name.toLowerCase(), v = String(a.value);
          var keep = (n === 'href' && /^(https?:|mailto:|tel:)/i.test(v)) ||
            (n === 'src' && c.tagName === 'IMG' && /^(media|thumb)\/[A-Za-z0-9_.-]+$/.test(v)) ||
            (n === 'type' && c.tagName === 'INPUT') || (n === 'checked' && c.tagName === 'INPUT') || (n === 'disabled') ||
            n === 'alt' || n === 'class' || (n === 'align') || n === 'colspan' || n === 'rowspan';
          if (!keep) c.removeAttribute(a.name);
        });
        if (c.tagName === 'INPUT' && c.getAttribute('type') !== 'checkbox') c.remove();
        walk(c);
      });
    })(t.content);
    return t.innerHTML;
  }

  function html(md) {
    setupMarked();
    if (!root.marked) return esc(md).replace(/\n/g, '<br>');
    return sanitize(root.marked.parse(String(md || '')));
  }

  function inline(md) {
    setupMarked();
    if (!root.marked) return esc(md);
    return sanitize(root.marked.parseInline(String(md || '')));
  }

  var api = { task: task, taskText: taskText, outline: outline, outlineBody: outlineBody, parse: parse, serialize: serialize, serializeBlock: serializeBlock, fileLine: fileLine, kindOf: kindOf, plain: plain,
    title: title, summary: summary, html: html, inline: inline, esc: esc, sanitize: sanitize, FILES: FILES, baseName: baseName };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MD = api;
})(typeof window !== 'undefined' ? window : this);
