/* Phone mode for WhatsApp Web: ONE pane at a time (chat list OR conversation), always fitted to the screen.
   Panes are found by structure (the flex row that holds #side), not by size, because at phone width the
   original layout squeezes/overflows every pane. The chat view then measures itself and fixes overflow. */
(function () {
  if (window.__mw) { window.__mw.refresh(); return; }
  var style = document.createElement('style');
  style.id = 'mw-style';
  document.head.appendChild(style);

  var row = null, kids0 = [], forceList = false, lastChatOpen = false, timer = null, fitTimer = null, backBtn = null;
  var cur = { list: null, chat: null, others: [], hasChat: false };

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
    kids0 = [];
    if (!row) return false;
    // a pane squeezed to a few pixels still counts: any full-height child of the row
    kids0 = kidsOf(row).filter(function (k) { return k.offsetWidth > 0 && k.offsetHeight > 100; });
    return kids0.length >= 2;
  }

  // Which child of the row is the chat list, which is the conversation, which are extras (icon strip, ...)
  function roles() {
    var side = document.getElementById('side'), main = document.getElementById('main');
    var kids = kidsOf(row);
    var list = null, chat = null, hasChat = false;
    kids.forEach(function (k) { if (side && k.contains(side)) list = k; });
    if (main) kids.forEach(function (k) { if (k !== list && k.contains(main)) chat = k; });
    if (chat) hasChat = true;
    if (!chat) {      // id may differ in newer versions: a message composer / footer only exists in an open chat
      kids.forEach(function (k) {
        if (!chat && k !== list && k.querySelector && k.querySelector('footer,[contenteditable="true"],[data-testid="conversation-compose-box-input"]')) {
          chat = k; hasChat = true;
        }
      });
    }
    if (!list) list = kids0.slice(0, -1).reduce(function (a, b) { return !a || area(a) >= area(b) ? b : a; }, null);
    if (!chat) {
      var after = kids.filter(function (k) { return k !== list && kids0.indexOf(k) >= 0; });
      chat = after.length ? after[after.length - 1] : null;
    }
    var others = kids0.filter(function (k) { return k !== list && k !== chat; });
    cur = { list: list, chat: chat, others: others, hasChat: hasChat };
    return !!(list && chat);
  }

  function ensureBackBtn() {
    if (attached(backBtn)) return backBtn;
    backBtn = document.createElement('div');
    backBtn.id = 'mw-back';
    backBtn.textContent = '←';
    backBtn.addEventListener('click', function (e) { e.stopPropagation(); back(); });
    document.body.appendChild(backBtn);
    return backBtn;
  }

  function notify(v) { try { if (window.Android && window.Android.view) window.Android.view(v); } catch (e) {} }

  function clearMarks() {
    style.textContent = '';
    Array.prototype.forEach.call(document.querySelectorAll('[data-mw],[data-mw-anc],[data-mw-fit]'), function (e) {
      e.removeAttribute('data-mw'); e.removeAttribute('data-mw-anc'); e.removeAttribute('data-mw-fit');
      e.removeAttribute('data-mw-scale');
    });
  }

  function apply() {
    if (!(attached(row) && kids0.length && kids0.every(attached))) {
      clearMarks();
      if (!detect()) { notify('none'); return; }
    }
    if (!roles()) { notify('none'); return; }
    var chatOpen = cur.hasChat;
    if (chatOpen && !lastChatOpen) forceList = false;     // a chat was just opened
    lastChatOpen = chatOpen;
    var showChat = chatOpen && !forceList;

    // clear role marks, then set them again
    Array.prototype.forEach.call(document.querySelectorAll('[data-mw]'), function (e) { e.removeAttribute('data-mw'); });
    row.setAttribute('data-mw', 'row');
    cur.list.setAttribute('data-mw', 'list');
    cur.chat.setAttribute('data-mw', 'chat');
    cur.others.forEach(function (r) { r.setAttribute('data-mw', 'other'); });
    // every ancestor (of the row, and of the conversation) must be allowed to shrink to the phone width
    [row, cur.chat].forEach(function (start) {
      for (var a = start.parentElement; a && a !== document.body; a = a.parentElement) a.setAttribute('data-mw-anc', '1');
    });
    document.documentElement.setAttribute('data-mw-view', showChat ? 'chat' : 'list');
    ensureBackBtn().style.display = showChat ? 'flex' : 'none';
    notify(showChat ? 'chat' : 'list');

    style.textContent =
      'html,body,#app{min-width:0!important;max-width:100vw!important;overflow-x:hidden!important;-webkit-text-size-adjust:100%}' +
      '[data-mw-anc]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
      '[data-mw=row]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
      '[data-mw=other]{display:none!important}' +
      '[data-mw=list]{flex:1 1 0!important;width:auto!important;min-width:0!important;max-width:none!important}' +
      '[data-mw=chat]{flex:1 1 100%!important;width:100%!important;min-width:0!important;max-width:100vw!important;' +
      'left:0!important;right:auto!important;margin-left:0!important;transform:none!important;position:relative!important}' +
      '[data-mw-fit]{max-width:100%!important;min-width:0!important}' +
      '[data-mw=chat][data-mw-scale]{transform:scale(var(--mw-s))!important;transform-origin:0 0!important;' +
      'width:calc(100% / var(--mw-s))!important;max-width:none!important;height:calc(100% / var(--mw-s))!important}' +
      '[data-mw-vis]{overflow:visible!important;width:auto!important;min-width:max-content!important;max-width:none!important;flex:0 0 auto!important}' +
      '[data-mw-pass]{pointer-events:none!important}' +
      '[data-mw=chat] [data-id],[data-mw=chat] .message-in,[data-mw=chat] .message-out{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}' +
      'html{touch-action:manipulation}' +
      'html[data-mw-view=chat] [data-mw=list]{display:none!important}' +
      'html[data-mw-view=list] [data-mw=chat]{display:none!important}' +
      'html[data-mw-view=chat] [data-mw=chat] header{padding-left:46px!important}' +
      '[aria-label="New chat"]{}' +
      'html[data-mw-view=list] [aria-label="New chat"]{position:fixed!important;right:16px;bottom:84px;z-index:50;' +
      'width:56px;height:56px;border-radius:16px;box-shadow:0 3px 8px rgba(0,0,0,.3)}' +
      '#mw-back{position:fixed;left:4px;top:14px;width:40px;height:40px;z-index:2147483647;display:none;' +
      'align-items:center;justify-content:center;font:400 26px/1 sans-serif;color:#54656f}' +
      '@media (prefers-color-scheme:dark){#mw-back{color:#aebac1}}';
    if (showChat) scheduleFit();
  }

  // Geometry pass: anything inside the conversation that is still wider than the screen gets squeezed;
  // if something stubborn remains, the whole conversation is scaled down so nothing leaves the screen.
  function fit() {
    var host = cur.chat;
    if (!attached(host) || document.documentElement.getAttribute('data-mw-view') !== 'chat') return;
    var vw = document.documentElement.clientWidth;
    host.removeAttribute('data-mw-scale'); host.style.removeProperty('--mw-s');   // measure unscaled
    for (var pass = 0; pass < 3; pass++) {
      var els = host.querySelectorAll('*'), changed = false, n = 0;
      for (var i = 0; i < els.length && n < 4000; i++, n++) {
        var e = els[i];
        if (e.hasAttribute('data-mw-fit')) continue;
        var r = e.getBoundingClientRect();
        if (r.width > vw + 2 && r.height > 0) { e.setAttribute('data-mw-fit', '1'); changed = true; }
      }
      if (!changed) break;
    }
    var right = 0, els2 = host.querySelectorAll('*'), m = 0;
    for (var j = 0; j < els2.length && m < 4000; j++, m++) {
      var rr = els2[j].getBoundingClientRect();
      if (rr.width > 0 && rr.height > 0 && rr.right > right && rr.left < vw * 2) right = rr.right;
    }
    if (right > vw + 4) { host.style.setProperty('--mw-s', String(Math.max(0.5, vw / right))); host.setAttribute('data-mw-scale', '1'); }
  }
  function scheduleFit() {
    [0, 350, 1200].forEach(function (ms) { setTimeout(function () { fit(); audit(); }, ms); });
  }


  // ---- Tap audit: every control in the chat header and message bar must be reachable by a finger.
  // A control is "blocked" if the element at its centre is not itself (clipped by a narrow parent, covered by an overlay).
  var lastAudit = { checked: 0, blocked: 0, fixed: 0, notes: [] };
  var CONTROLS = 'button,[role="button"],[tabindex="0"],[data-icon],[contenteditable="true"],input,a[href]';
  function blockedControls(host) {
    var out = [], els = host.querySelectorAll('header ' + CONTROLS.split(',').join(', header ') + ', footer ' + CONTROLS.split(',').join(', footer '));
    var vw = window.innerWidth, vh = window.innerHeight, n = 0;
    for (var i = 0; i < els.length && n < 300; i++, n++) {
      var el = els[i], r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) continue;
      var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none') continue;
      if (cx < 0 || cy < 0 || cx > vw || cy > vh) { out.push({ el: el, top: null }); continue; }
      var t = document.elementFromPoint(cx, cy);
      if (!t || el.contains(t) || t.contains(el) || t.id === 'mw-back') continue;
      out.push({ el: el, top: t });
    }
    return { list: out, checked: n };
  }
  function audit() {
    var host = cur.chat;
    if (!attached(host) || document.documentElement.getAttribute('data-mw-view') !== 'chat') return;
    var a = blockedControls(host), fixed = 0;
    lastAudit = { checked: a.checked, blocked: a.list.length, fixed: 0, notes: [] };
    a.list.slice(0, 40).forEach(function (b) {
      lastAudit.notes.push(describe(b.el) + (b.top ? '  covered by ' + describe(b.top) : '  off-screen'));
      // let every container between the control and the header/footer grow to fit its content
      for (var p = b.el.parentElement; p && p !== host && p.tagName !== 'HEADER' && p.tagName !== 'FOOTER'; p = p.parentElement)
        p.setAttribute('data-mw-vis', '1');
      // a transparent, empty layer sitting on top of a control is made click-through
      var t = b.top;
      if (t && t !== host && !t.querySelector(CONTROLS) && !(t.textContent || '').trim() && !t.closest('[data-mw-pass]')) t.setAttribute('data-mw-pass', '1');
    });
    if (a.list.length) {
      fit();                                   // the header may now be wider: shrink to the screen again
      var b2 = blockedControls(host);
      lastAudit.fixed = Math.max(0, a.list.length - b2.list.length);
      lastAudit.blocked = b2.list.length;
      b2.list.slice(0, 5).forEach(function (b) { lastAudit.notes.push('STILL BLOCKED ' + describe(b.el) + (b.top ? ' by ' + describe(b.top) : '')); });
    }
  }

  // ---- Long-press a message => open its action menu (WhatsApp Web only offers it on mouse hover / right-click)
  var lp = null, suppressClickUntil = 0;
  function msgOf(t) { return t && t.closest ? t.closest('[data-id],.message-in,.message-out,[data-testid="msg-container"]') : null; }
  document.addEventListener('touchstart', function (e) {
    if (document.documentElement.getAttribute('data-mw-view') !== 'chat' || e.touches.length !== 1) return;
    var m = msgOf(e.target);
    if (!m || e.target.closest('footer,header')) return;
    var t = e.touches[0];
    lp = { x: t.clientX, y: t.clientY, el: e.target, fired: false };
    lp.timer = setTimeout(function () {
      if (!lp) return;
      lp.fired = true;
      suppressClickUntil = Date.now() + 700;
      var ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: lp.x, clientY: lp.y, button: 2, buttons: 2, view: window });
      lp.el.dispatchEvent(ev);
      try { if (navigator.vibrate) navigator.vibrate(15); } catch (x) {}
    }, 520);
  }, { passive: true, capture: true });
  document.addEventListener('touchmove', function (e) {
    if (!lp) return;
    var t = e.touches[0];
    if (Math.abs(t.clientX - lp.x) > 10 || Math.abs(t.clientY - lp.y) > 10) { clearTimeout(lp.timer); lp = null; }
  }, { passive: true, capture: true });
  function endTouch() { if (lp) { clearTimeout(lp.timer); lp = null; } }
  document.addEventListener('touchend', endTouch, { passive: true, capture: true });
  document.addEventListener('touchcancel', endTouch, { passive: true, capture: true });
  document.addEventListener('click', function (e) {
    if (Date.now() < suppressClickUntil) { e.stopPropagation(); e.preventDefault(); suppressClickUntil = 0; }
  }, true);
  document.addEventListener('contextmenu', function (e) {
    // keep the page's own menu; just stop the Android text-selection popup from also appearing
    if (e.isTrusted && msgOf(e.target)) e.preventDefault();
  }, true);

  function schedule() {
    clearTimeout(timer); timer = setTimeout(apply, 80);
    clearTimeout(fitTimer); fitTimer = setTimeout(fit, 500);
  }

  function back() {
    if (document.documentElement.getAttribute('data-mw-view') !== 'chat') return false;
    // First try Escape: WhatsApp Web closes menus, the emoji tray, attach menu and info panels with it.
    var OV = '[role="dialog"],[role="menu"],[role="listbox"],[aria-modal="true"],[data-animate-dropdown-item],[data-animate-modal-popup],[data-testid="emoji-panel"]';
    function snap() { return document.getElementsByTagName('*').length + document.querySelectorAll(OV).length * 1000; }
    var before = snap(), tgt = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (t) {
      tgt.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
    });
    setTimeout(function () {
      // something closed (menu / emoji tray / panel) => stay in the chat; otherwise go back to the chat list
      if (snap() === before && document.documentElement.getAttribute('data-mw-view') === 'chat') { forceList = true; apply(); }
    }, 300);
    return true;
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
    var out = ['viewport ' + window.innerWidth + 'x' + window.innerHeight + ', scrollWidth ' + document.documentElement.scrollWidth,
               'row: ' + describe(row), 'list: ' + describe(cur.list), 'chat: ' + describe(cur.chat) + (cur.chat && cur.chat.hasAttribute('data-mw-scale') ? ' scaled=' + cur.chat.style.getPropertyValue('--mw-s') : ''),
               'others: ' + (cur.others.map(describe).join(' | ') || '(none)'),
               'view: ' + document.documentElement.getAttribute('data-mw-view') + ', chatOpen: ' + cur.hasChat,
               '#side: ' + !!document.getElementById('side') + ', #main: ' + !!document.getElementById('main'),
               'row children:'];
    if (row) kidsOf(row).forEach(function (k) { out.push('  ' + describe(k)); });
    out.push('tap audit: checked ' + lastAudit.checked + ', blocked ' + lastAudit.blocked + ', repaired ' + lastAudit.fixed);
    lastAudit.notes.slice(0, 6).forEach(function (n) { out.push('  ' + n); });
    if (cur.chat) {
      var wide = Array.prototype.slice.call(cur.chat.querySelectorAll('*')).map(function (e) { return [e.getBoundingClientRect().width, e]; })
        .sort(function (a, b) { return b[0] - a[0]; }).slice(0, 5);
      out.push('widest in chat:');
      wide.forEach(function (w) { out.push('  ' + describe(w[1]) + ' w=' + Math.round(w[0])); });
    }
    return out.join('\n');
  }

  new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
  // tapping a chat in the list always switches to the conversation, even if it is already the open one
  document.addEventListener('click', function (e) {
    if (attached(cur.list) && cur.list.contains(e.target) && e.target.closest &&
        e.target.closest('#pane-side,[role="listitem"],[role="row"],[data-testid="cell-frame-container"]')) {
      forceList = false;
      setTimeout(apply, 60);
    }
  }, true);

  window.__mw = { refresh: apply, back: back, debug: debug, nav: nav, fit: fit, audit: audit };
  apply();
})();
