'use strict';
// GTD (Getting Things Done) with the Eisenhower matrix (docs/GTD.md).
// A note can be on a GTD list (frontmatter "gtd"), and check list items are actions too
// (Obsidian Tasks style: "#重要 #緊急 📅 2026-11-01"). Important / urgent sort actions into the
// four quadrants; an action due within a few days counts as urgent without being marked.
(function (root) {
  var LISTS = [
    ['inbox', '受信箱', '📥', 'とりあえず入れておく。あとで「整理する」'],
    ['next', '次にやる', '▶', 'すぐに取りかかれる行動'],
    ['waiting', '待ち', '⏳', '人に任せた・返事待ち'],
    ['scheduled', '予定', '📅', '日にちが決まっている'],
    ['project', 'プロジェクト', '📁', '2つ以上の行動が要るもの。子メモで分ける'],
    ['someday', 'いつか', '💭', 'いつか・多分やる'],
    ['reference', '資料', '📚', '行動は要らないが残しておく'],
    ['done', '完了', '✅', '終わったもの']
  ];
  var QUADS = [
    { q: 1, imp: true, urg: true, name: '今すぐやる', hint: '重要・緊急', cls: 'q1' },
    { q: 2, imp: true, urg: false, name: '計画してやる', hint: '重要・緊急でない', cls: 'q2' },
    { q: 3, imp: false, urg: true, name: '任せる・手早く', hint: '緊急・重要でない', cls: 'q3' },
    { q: 4, imp: false, urg: false, name: 'やめる・あとで', hint: 'どちらでもない', cls: 'q4' }
  ];
  var ACTIVE = { next: 1, waiting: 1, scheduled: 1, project: 1 };

  function pad(n) { return ('0' + n).slice(-2); }
  function day(d) { d = d || new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function addDays(n) { var d = new Date(); d.setDate(d.getDate() + n); return day(d); }
  function label(key) { for (var i = 0; i < LISTS.length; i++) if (LISTS[i][0] === key) return LISTS[i]; return null; }
  function shortDate(s) { if (!s) return ''; var p = s.split('-'); return (+p[1]) + '/' + (+p[2]); }

  function live(n) { return n && !n.trashed && !n.archived; }

  // Every open action: GTD notes on an active list, and open check list items that are marked
  // (or live in a GTD note). [{kind:'note'|'item', note, k (item ordinal), item}]
  function actions(notes) {
    var out = [];
    notes.forEach(function (n) {
      if (!live(n)) return;
      if (ACTIVE[n.gtd]) out.push({ kind: 'note', note: n, id: n.id });
      if (n.gtd === 'done' || n.gtd === 'reference' || n.gtd === 'someday') return;
      var k = -1;
      MD.parse(n.body).forEach(function (b) {
        if (b.t !== 'list') return;
        b.items.forEach(function (it) {
          k++;
          if (it.checked || !it.text.trim()) return;
          if (it.imp || it.urg || it.due || ACTIVE[n.gtd] || n.gtd === 'inbox')
            out.push({ kind: 'item', note: n, k: k, item: it, id: n.id + '#' + k });
        });
      });
    });
    return out;
  }

  function soonDays() { return +((root.App && App.state.urgentDays) || 2); }

  function due(a) { return a.kind === 'note' ? a.note.due || '' : a.item.due || ''; }
  function isImp(a) { return a.kind === 'note' ? !!a.note.important : !!a.item.imp; }
  function isUrgMarked(a) { return a.kind === 'note' ? !!a.note.urgent : !!a.item.urg; }
  function isUrg(a) { var d = due(a); return isUrgMarked(a) || (!!d && d <= addDays(soonDays())); }
  function quadrant(a) { var i = isImp(a), u = isUrg(a); return i && u ? 1 : i ? 2 : u ? 3 : 4; }
  function text(a) { return a.kind === 'note' ? (MD.title(a.note) || '（タイトルなし）') : MD.plain(a.item.text); }

  // Change one check list item of a note (by ordinal) and return the new body.
  function editItem(n, k, change) {
    var blocks = MD.parse(n.body), i = -1;
    blocks.forEach(function (b) {
      if (b.t !== 'list') return;
      b.items.forEach(function (it) { i++; if (i === k) change(it); });
    });
    return MD.serialize(blocks);
  }

  function update(a, change) {
    var n = JSON.parse(JSON.stringify(App.byId[a.note.id]));
    if (a.kind === 'note') change(n, null);
    else n.body = editItem(n, a.k, function (it) { change(n, it); });
    App.saveNote(n);
    return n;
  }

  // Moving to a quadrant sets the marks; "urgent" from a near due date stays as it is.
  function setQuadrant(a, q) {
    var Q = QUADS[q - 1];
    update(a, function (n, it) {
      var dueSoon = !!due(a) && due(a) <= addDays(soonDays());
      var urg = Q.urg && !dueSoon ? true : Q.urg ? isUrgMarked(a) : false;
      if (it) { it.imp = Q.imp; it.urg = urg; }
      else { n.important = Q.imp; n.urgent = urg; if (!n.gtd) n.gtd = 'next'; }
      if (!Q.urg && dueSoon) App.toast('期限が近いので「緊急」のままです（期限を変えると移ります）');
    });
  }

  function complete(a, done) {
    update(a, function (n, it) {
      if (it) { it.checked = done; it.doneDate = done ? day() : ''; }
      else { n.gtd = done ? 'done' : 'next'; n.done = done ? Date.now() : 0; }
    });
  }

  // ---- the matrix view ------------------------------------------------------------------

  function matrix(wall) {
    var el = App.el;
    var acts = actions(App.notes);
    var q = App.view.query ? App.norm(App.view.query) : '';
    if (q) acts = acts.filter(function (a) { return App.norm(text(a) + ' ' + MD.title(a.note)).indexOf(q) >= 0; });
    var grid = el('div', 'matrix');
    QUADS.forEach(function (Q) {
      var box = el('section', 'quad ' + Q.cls);
      box.dataset.q = Q.q;
      var head = el('div', 'quad-head');
      var t = el('div', 'quad-title');
      t.appendChild(el('b', '', Q.name));
      t.appendChild(el('span', '', Q.hint));
      head.appendChild(t);
      var list = acts.filter(function (a) { return quadrant(a) === Q.q; }).sort(function (x, y) {
        var dx = due(x) || '9999', dy = due(y) || '9999';
        return dx < dy ? -1 : dx > dy ? 1 : y.note.updated - x.note.updated;
      });
      head.appendChild(el('span', 'quad-count', list.length + ''));
      var add = el('button', 'icon', '＋');
      add.title = 'この欄に新しい行動';
      add.onclick = function () {
        var n = App.blank();
        n.gtd = 'next';
        n.important = Q.imp;
        n.urgent = Q.urg;
        App.openNote(n, { isNew: true, kind: 'text' });
      };
      head.appendChild(add);
      box.appendChild(head);
      var ul = el('div', 'quad-list');
      list.forEach(function (a) { ul.appendChild(row(a)); });
      if (!list.length) ul.appendChild(el('div', 'quad-empty', Q.q === 1 ? 'いま急ぎのことはありません' : 'ありません'));
      box.appendChild(ul);
      grid.appendChild(box);
    });
    wall.appendChild(grid);
    var info = el('p', 'muted matrix-help', '長押しして別の欄へ動かせます。期限が' + soonDays() + '日以内の行動は自動で「緊急」になります。');
    wall.appendChild(info);
  }

  function row(a) {
    var el = App.el;
    var r = el('div', 'act' + (a.kind === 'item' ? ' item' : ''));
    var cb = el('input');
    cb.type = 'checkbox';
    cb.onclick = function (e) { e.stopPropagation(); };
    cb.onchange = function () { r.classList.add('gone'); setTimeout(function () { complete(a, true); App.toast('完了にしました'); }, 250); };
    r.appendChild(cb);
    var body = el('div', 'act-body');
    body.appendChild(el('div', 'act-text', text(a)));
    var sub = el('div', 'act-sub');
    var d = due(a);
    if (d) {
      var c = el('span', 'due' + (d < day() ? ' over' : d <= addDays(soonDays()) ? ' soon' : ''), '📅 ' + shortDate(d));
      sub.appendChild(c);
    }
    if (a.kind === 'item') sub.appendChild(el('span', '', MD.title(a.note) || 'メモ'));
    else {
      var L = label(a.note.gtd);
      if (L && a.note.gtd !== 'next') sub.appendChild(el('span', '', L[2] + ' ' + L[1]));
      if (a.note.waitingFor) sub.appendChild(el('span', '', '⏳ ' + a.note.waitingFor));
      (a.note.contexts || []).forEach(function (x) { sub.appendChild(el('span', 'ctx', x)); });
    }
    if (sub.childNodes.length) body.appendChild(sub);
    r.appendChild(body);
    var mv = el('button', 'icon act-move', '⇄');
    mv.title = '別の欄へ';
    mv.onclick = function (e) {
      e.stopPropagation();
      App.menu(mv, QUADS.map(function (Q) { return [(quadrant(a) === Q.q ? '● ' : '○ ') + Q.name + '（' + Q.hint + '）', function () { setQuadrant(a, Q.q); }]; }));
    };
    r.appendChild(mv);
    r.onclick = function () { if (!r.dataset.dragged) App.openNote(App.byId[a.note.id]); };
    drag(r, a);
    return r;
  }

  // Long press, then drag the row onto another quadrant.
  function drag(r, a) {
    var timer = null, ghost = null, sx = 0, sy = 0, pid = null;
    r.addEventListener('pointerdown', function (e) {
      if (e.target.tagName === 'INPUT' || e.target.classList.contains('act-move')) return;
      sx = e.clientX; sy = e.clientY; pid = e.pointerId;
      delete r.dataset.dragged;
      timer = setTimeout(function () {
        timer = null;
        r.dataset.dragged = '1';
        try { r.setPointerCapture(pid); } catch (x) {}
        ghost = r.cloneNode(true);
        ghost.className = 'act ghost';
        ghost.style.width = r.offsetWidth + 'px';
        document.body.appendChild(ghost);
        move(e.clientX, e.clientY);
        if (navigator.vibrate) try { navigator.vibrate(15); } catch (x) {}
      }, 380);
    });
    function target(x, y) {
      var t = document.elementFromPoint(x, y);
      return t && t.closest ? t.closest('.quad') : null;
    }
    function move(x, y) {
      ghost.style.left = (x - 30) + 'px';
      ghost.style.top = (y - 20) + 'px';
      Array.prototype.forEach.call(document.querySelectorAll('.quad'), function (qd) { qd.classList.remove('drop'); });
      ghost.style.display = 'none';
      var q = target(x, y);
      ghost.style.display = '';
      if (q) q.classList.add('drop');
    }
    r.addEventListener('pointermove', function (e) {
      if (timer && Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy) > 10) { clearTimeout(timer); timer = null; }
      if (ghost) { e.preventDefault(); move(e.clientX, e.clientY); }
    });
    function end(e) {
      clearTimeout(timer);
      timer = null;
      if (!ghost) return;
      ghost.remove();
      ghost = null;
      var q = target(e.clientX, e.clientY);
      Array.prototype.forEach.call(document.querySelectorAll('.quad'), function (qd) { qd.classList.remove('drop'); });
      if (q && +q.dataset.q !== quadrant(a)) setQuadrant(a, +q.dataset.q);
      setTimeout(function () { delete r.dataset.dragged; }, 50);
    }
    r.addEventListener('pointerup', end);
    r.addEventListener('pointercancel', end);
    r.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  }

  // ---- clarify an inbox note (the GTD questions) ------------------------------------------

  function clarify(n, done) {
    var el = App.el;
    var body = el('div', 'clarify');
    var close = null;
    var marks = { imp: !!n.important, urg: !!n.urgent };
    function finish(change) {
      change(n);
      if (n.gtd !== 'done' && n.gtd !== 'reference' && n.gtd !== 'someday') { n.important = marks.imp; n.urgent = marks.urg; }
      if (close) close();
      done(n);
    }
    function step(q, answers, extra) {
      body.innerHTML = '';
      body.appendChild(el('div', 'clarify-note', MD.title(n) || '（タイトルなし）'));
      body.appendChild(el('h3', '', q));
      if (extra) body.appendChild(extra);
      answers.forEach(function (x) {
        var b = el('button', 'choice', '');
        b.appendChild(el('b', '', x[0]));
        if (x[2]) b.appendChild(el('span', '', x[2]));
        b.onclick = x[1];
        body.appendChild(b);
      });
    }
    function matrixMarks() {
      var w = el('div', 'clarify-marks');
      [['imp', '★ 重要'], ['urg', '⚡ 緊急']].forEach(function (m) {
        var b = el('button', 'toggle' + (marks[m[0]] ? ' on' : ''), m[1]);
        b.onclick = function () { marks[m[0]] = !marks[m[0]]; b.classList.toggle('on', marks[m[0]]); };
        w.appendChild(b);
      });
      return w;
    }
    function s1() {
      step('行動が必要ですか？', [
        ['はい', s2],
        ['いいえ・資料として残す', function () { finish(function (x) { x.gtd = 'reference'; }); }, '「資料」へ'],
        ['いいえ・いつかやるかも', function () { finish(function (x) { x.gtd = 'someday'; }); }, '「いつか」へ'],
        ['いいえ・要らない', function () { finish(function (x) { x.trashed = Date.now(); x.gtd = ''; }); }, 'ゴミ箱へ']
      ]);
    }
    function s2() {
      step('2分以内に終わりますか？', [
        ['はい（いまやる）', function () { finish(function (x) { x.gtd = 'done'; x.done = Date.now(); }); }, 'やったら完了に'],
        ['いいえ', s3]
      ]);
    }
    function s3() {
      step('1回の行動で終わりますか？', [
        ['はい', s4],
        ['いいえ・何段階かある', function () {
          var input = el('input', 'wide');
          input.placeholder = '最初の行動（例：見積もりを3社に頼む）';
          step('プロジェクトにします。最初の行動は？', [
            ['プロジェクトにする', function () {
              var first = input.value.trim();
              finish(function (x) { x.gtd = 'project'; });
              if (first) {
                var c = App.blank();
                c.title = first;
                c.gtd = 'next';
                c.parent = n.id;
                c.important = marks.imp;
                c.urgent = marks.urg;
                App.saveNote(c);
              }
            }, '最初の行動は子メモ（次にやる）になります']
          ], wrap([input, matrixMarks()]));
          setTimeout(function () { input.focus(); }, 50);
        }]
      ]);
    }
    function s4() {
      step('誰がやりますか？', [
        ['自分', s5],
        ['ほかの人に任せる', function () {
          var input = el('input', 'wide');
          input.placeholder = '誰に（例：山田さん）';
          step('「待ち」にします', [['待ちにする', function () { finish(function (x) { x.gtd = 'waiting'; x.waitingFor = input.value.trim(); }); }]], wrap([input, matrixMarks()]));
          setTimeout(function () { input.focus(); }, 50);
        }]
      ]);
    }
    function s5() {
      var date = el('input', 'wide');
      date.type = 'date';
      date.value = n.due || '';
      step('日にちが決まっていますか？', [
        ['決まっている → 予定', function () {
          if (!date.value) { App.toast('日付を選んでください'); return; }
          finish(function (x) { x.gtd = 'scheduled'; x.due = date.value; });
        }, '上で日付を選んでから'],
        ['決まっていない → 次にやる', function () { finish(function (x) { x.gtd = 'next'; if (date.value) x.due = date.value; }); }, '期限だけ付けることもできます']
      ], wrap([date, matrixMarks()]));
    }
    function wrap(nodes) { var d = el('div'); nodes.forEach(function (x) { d.appendChild(x); }); return d; }
    s1();
    close = App.sheet('整理する', body, [['あとで']]);
  }

  // ---- weekly review --------------------------------------------------------------------------

  function review() {
    var el = App.el;
    var notes = App.notes.filter(live);
    var today = day();
    var acts = actions(App.notes);
    var inbox = notes.filter(function (n) { return n.gtd === 'inbox'; });
    var overdue = acts.filter(function (a) { return due(a) && due(a) < today; });
    var waiting = notes.filter(function (n) { return n.gtd === 'waiting'; });
    var projects = notes.filter(function (n) { return n.gtd === 'project'; });
    var stalled = projects.filter(function (p) {
      var kids = notes.filter(function (c) { return c.parent === p.id && ACTIVE[c.gtd] && c.gtd !== 'project'; });
      var items = acts.filter(function (a) { return a.kind === 'item' && a.note.id === p.id; });
      return !kids.length && !items.length;
    });
    var weekAgo = Date.now() - 7 * 86400000;
    var doneWeek = App.notes.filter(function (n) { return n.gtd === 'done' && n.done > weekAgo; });
    var body = el('div', 'review');
    function line(icon, label, count, note, go) {
      var b = el('button', 'review-row' + (count ? '' : ' zero'));
      b.appendChild(el('span', 'ic', icon));
      var t = el('span', 'tx');
      t.appendChild(el('b', '', label));
      if (note) t.appendChild(el('span', '', note));
      b.appendChild(t);
      b.appendChild(el('span', 'n', count + ''));
      b.onclick = function () { close(); go(); };
      body.appendChild(b);
    }
    var close;
    line('📥', '受信箱を空にする', inbox.length, '1つずつ「整理する」', function () { App.showSection('gtd', 'inbox'); });
    line('⚠', '期限切れ', overdue.length, '日付を変えるか、やめる', function () { App.showSection('matrix'); });
    line('📁', '次の行動が無いプロジェクト', stalled.length, stalled.slice(0, 3).map(function (p) { return MD.title(p); }).join('・'), function () { App.showSection('gtd', 'project'); });
    line('⏳', '待ち（催促が要るか）', waiting.length, '', function () { App.showSection('gtd', 'waiting'); });
    line('💭', 'いつか（今週やるものは？）', notes.filter(function (n) { return n.gtd === 'someday'; }).length, '', function () { App.showSection('gtd', 'someday'); });
    line('▦', 'マトリクスを見直す', acts.length, '「計画してやる」に時間を', function () { App.showSection('matrix'); });
    body.appendChild(el('p', 'muted', 'この1週間で完了：' + doneWeek.length + ' 件'));
    close = App.sheet('週次レビュー', body, [['閉じる']]);
  }

  root.GTD = {
    LISTS: LISTS, QUADS: QUADS, ACTIVE: ACTIVE, label: label, actions: actions, quadrant: quadrant, setQuadrant: setQuadrant,
    complete: complete, matrix: matrix, clarify: clarify, review: review, day: day, addDays: addDays, shortDate: shortDate,
    soonDays: soonDays, editItem: editItem
  };
})(window);
