/* Phone mode for WhatsApp Web: show ONE pane at a time (chat list OR conversation) so it fits the screen.
   Panes are found by structure (the flex row that holds #side), not by size, because at phone width the
   original layout squeezes every pane to a few dozen pixels. */
(function () {
  if (window.__mw) { window.__mw.refresh(); return; }
  var style = document.createElement('style');
  style.id = 'mw-style';
  document.head.appendChild(style);

  var row = null, list = null, right = null, rails = [], forceList = false, lastChatOpen = false, timer = null, backBtn = null;

  function attached(n) { return !!n && document.documentElement.contains(n); }

  function kidsOf(n) {
    return Array.prototype.filter.call(n.children, function (c) {
      var t = c.tagName;
      return t !== 'SCRIPT' && t !== 'STYLE' && t !== 'LINK' && c.id !== 'mw-back' && c.id !== 'mw-style';
    });
  }
  function big(k) { return k.offsetWidth > 30 && k.offsetHeight > 100; }
  function area(k) { return k.offsetWidth * k.offsetHeight; }

  // Top-most horizontal flex/grid row with at least two sizeable children.
  function findRow() {
    var n = document.getElementById('app') || document.body, guard = 0;
    while (n && guard++ < 14) {
      var ks = kidsOf(n);
      if (!ks.length) break;
      var cs = getComputedStyle(n);
      var rowLike = ((cs.display === 'flex' || cs.display === 'inline-flex') && cs.flexDirection.indexOf('row') === 0) ||
                    cs.display === 'grid';
      if (rowLike && ks.filter(big).length >= 2) return n;
      n = ks.reduce(function (a, b) { return area(a) >= area(b) ? a : b; });
    }
    // fallback: climb from #side to the first ancestor that has a sizeable sibling
    var s = document.getElementById('side'), c = s;
    while (c && c.parentElement && c.parentElement !== document.body) {
      var p = c.parentElement;
      if (kidsOf(p).some(function (k) { return k !== c && big(k); })) return p;
      c = p;
    }
    return null;
  }

  function detect() {
    row = findRow();
    list = right = null; rails = [];
    if (!row) return false;
    var side = document.getElementById('side');
    var kids = kidsOf(row).filter(function (k) { return k.offsetWidth > 0 || k.offsetHeight > 0; });
    if (kids.length < 2) return false;
    kids.forEach(function (k) { if (side && k.contains(side)) list = k; });
    if (!list) list = kids.slice(0, -1).reduce(function (a, b) { return area(a) >= area(b) ? a : b; });
    var after = kids.slice(kids.indexOf(list) + 1);
    right = after.length ? after[after.length - 1] : null;
    if (!right) return false;
    rails = kids.filter(function (k) { return k !== list && k !== right; });
    return true;
  }

  function ensureBackBtn() {
    if (attached(backBtn)) return backBtn;
    backBtn = document.createElement('div');
    backBtn.id = 'mw-back';
    backBtn.textContent = '‹';
    backBtn.addEventListener('click', function (e) { e.stopPropagation(); back(); });
    document.body.appendChild(backBtn);
    return backBtn;
  }

  function apply() {
    if (!(attached(row) && attached(list) && attached(right))) {
      style.textContent = '';                       // measure the original layout again
      Array.prototype.forEach.call(document.querySelectorAll('[data-mw],[data-mw-anc],[data-mw-in]'), function (e) {
        e.removeAttribute('data-mw'); e.removeAttribute('data-mw-anc'); e.removeAttribute('data-mw-in');
      });
      if (!detect()) { notify('none'); return; }
    }
    var chatOpen = !!document.getElementById('main');
    if (chatOpen && !lastChatOpen) forceList = false;     // a chat was just opened
    lastChatOpen = chatOpen;
    var showChat = chatOpen && !forceList;

    row.setAttribute('data-mw', 'row');
    list.setAttribute('data-mw', 'list');
    right.setAttribute('data-mw', 'right');
    rails.forEach(function (r) { r.setAttribute('data-mw', 'rail'); });
    for (var a = row.parentElement; a && a !== document.body; a = a.parentElement) a.setAttribute('data-mw-anc', '1');
    var side = document.getElementById('side');
    for (var i = side; i && i !== row && i !== list.parentElement; i = i.parentElement) i.setAttribute('data-mw-in', '1');
    document.documentElement.setAttribute('data-mw-view', showChat ? 'chat' : 'list');
    ensureBackBtn().style.display = showChat ? 'flex' : 'none';
    notify(showChat ? 'chat' : 'list');

    style.textContent =
      'html,body,#app{min-width:0!important;max-width:100vw!important;overflow-x:hidden!important}' +
      '[data-mw-anc]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
      '[data-mw=row]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
      '[data-mw=rail]{display:none!important}' +
      'html[data-mw-view=list] [aria-label="New chat"]{position:fixed!important;right:16px;bottom:84px;z-index:50;' +
      'width:56px;height:56px;border-radius:16px;box-shadow:0 3px 8px rgba(0,0,0,.3)}' +
      '[data-mw=list]{flex:1 1 0!important;width:auto!important;min-width:0!important;max-width:none!important}' +
      '[data-mw=right]{flex:1 1 100%!important;width:100%!important;min-width:0!important;max-width:100vw!important}' +
      '[data-mw-in]{min-width:0!important;max-width:100%!important}' +
      'html[data-mw-view=chat] [data-mw=list],html[data-mw-view=chat] [data-mw=rail]{display:none!important}' +
      'html[data-mw-view=list] [data-mw=right]{display:none!important}' +
      '#mw-back{position:fixed;left:0;top:10px;width:30px;height:46px;z-index:2147483647;display:none;' +
      'align-items:center;justify-content:center;font:700 30px/1 sans-serif;color:#fff;' +
      'background:rgba(0,0,0,.45);border-radius:0 23px 23px 0}';
  }

  function notify(v) { try { if (window.Android && window.Android.view) window.Android.view(v); } catch (e) {} }

  function schedule() { clearTimeout(timer); timer = setTimeout(apply, 80); }

  function back() {
    if (document.documentElement.getAttribute('data-mw-view') === 'chat') { forceList = true; apply(); return true; }
    return false;
  }


  var NAV = {
    chats: ['[aria-label="Chats"]', '[data-icon^="chats"]', '[data-icon="chat"]'],
    updates: ['[aria-label="Updates"]', '[aria-label="Status"]', '[data-icon^="status"]', '[data-icon^="updates"]'],
    communities: ['[aria-label="Communities"]', '[data-icon^="community"]', '[data-icon^="communities"]'],
    calls: ['[aria-label="Calls"]', '[data-icon^="call"]'],
    settings: ['[aria-label="Settings"]', '[data-icon^="settings"]']
  };
  function nav(tab) {
    var sels = NAV[tab] || [];
    for (var i = 0; i < sels.length; i++) {
      var el = document.querySelector(sels[i]);
      if (el) {
        (el.closest('button,[role=button],[role=tab],a,[tabindex]') || el).click();
        forceList = true;
        setTimeout(apply, 120);
        return true;
      }
    }
    return false;
  }

  function describe(n) {
    if (!n) return '(none)';
    var c = typeof n.className === 'string' ? n.className.split(' ')[0] : '';
    return n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + (c ? '.' + c : '') + ' ' + n.offsetWidth + 'x' + n.offsetHeight;
  }
  function debug() {
    var out = ['viewport ' + window.innerWidth + 'x' + window.innerHeight, 'row: ' + describe(row),
               'list: ' + describe(list), 'right: ' + describe(right),
               'rails: ' + (rails.map(describe).join(' | ') || '(none)'),
               'view: ' + document.documentElement.getAttribute('data-mw-view'),
               'has #side: ' + !!document.getElementById('side') + ', #main: ' + !!document.getElementById('main'),
               'row children:'];
    if (row) kidsOf(row).forEach(function (k) { out.push('  ' + describe(k)); });
    return out.join('\n');
  }

  new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
  // tapping a chat in the list always switches to the conversation, even if it is already the open one
  document.addEventListener('click', function (e) {
    if (attached(list) && list.contains(e.target) && e.target.closest && e.target.closest('#pane-side')) {
      forceList = false;
      setTimeout(apply, 60);
    }
  }, true);

  window.__mw = { refresh: apply, back: back, debug: debug, nav: nav };
  apply();
})();
