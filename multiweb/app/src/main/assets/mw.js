/* Phone mode for WhatsApp Web: ONE pane at a time (chat list OR conversation), fitted to the screen.
   Design rules (so WhatsApp's popups, emoji tray and attach menu are never disturbed):
   - the stylesheet is static and written once;
   - apply() only touches the DOM when the layout state actually changes;
   - measuring/repairing (fit, audit) runs once per opened chat, never while a menu/panel is open;
   - height-only resize events (soft keyboard, app bar) are not forwarded to the page, because WhatsApp
     closes menus on resize. */
(function () {
  if (window.__mw) { window.__mw.refresh(); return; }

  var CSS =
    'html,body,#app{min-width:0!important;max-width:100vw!important;overflow-x:hidden!important;-webkit-text-size-adjust:100%}' +
    'html{touch-action:manipulation}' +
    '[data-mw-anc]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
    '[data-mw=row]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
    '[data-mw=other]{display:none!important}' +
    '[data-mw=list]{flex:1 1 0!important;width:auto!important;min-width:0!important;max-width:none!important}' +
    '[data-mw=chat]{flex:1 1 100%!important;width:100%!important;min-width:0!important;max-width:100vw!important}' +
    '[data-mw-fit]{max-width:100%!important;min-width:0!important}' +
    '[data-mw-vis]{overflow:visible!important;width:auto!important;min-width:max-content!important;max-width:none!important;flex:0 0 auto!important}' +
    '[data-mw-pass]{pointer-events:none!important}' +
    '[data-mw-shrink]{min-width:0!important;flex:1 1 0!important;overflow:hidden!important;text-overflow:ellipsis}' +
    '[data-mw=chat] [data-id],[data-mw=chat] .message-in,[data-mw=chat] .message-out{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}' +
    'html[data-mw-view=chat] [data-mw=list]{display:none!important}' +
    'html[data-mw-view=list] [data-mw=chat]{display:none!important}' +
    'html[data-mw-view=chat] [data-mw=chat] header{padding-left:46px!important;box-sizing:border-box!important}' +
    'html[data-mw-view=list] [aria-label="New chat"]{position:fixed!important;right:16px;bottom:84px;z-index:50;width:56px;height:56px;border-radius:16px;box-shadow:0 3px 8px rgba(0,0,0,.3)}' +
    '#mw-back{position:fixed;left:4px;top:14px;width:40px;height:40px;z-index:2147483647;display:none;align-items:center;justify-content:center;font:400 26px/1 sans-serif;color:#54656f}' +
    '@media (prefers-color-scheme:dark){#mw-back{color:#aebac1}}';
  var style = document.createElement('style');
  style.id = 'mw-style';
  style.textContent = CSS;                       // written once, never changed
  document.head.appendChild(style);

  // Menus, emoji tray, attach menu, info panels... while one of these is open we do not touch the page.
  var OVERLAY = '[role="dialog"],[role="menu"],[role="listbox"],[aria-modal="true"],[data-animate-dropdown-item],' +
                '[data-animate-modal-popup],[data-testid*="emoji"],[data-testid*="attach"],[data-testid*="popup"],[data-testid*="menu"]';
  function overlayOpen() { return !!document.querySelector(OVERLAY); }

  var row = null, kids0 = [], cur = { list: null, chat: null, others: [], hasChat: false };
  var applied = { view: '', list: null, chat: null, others: '' };
  var forceList = false, lastChatOpen = false, lastMain = null, timer = null, backBtn = null, lastNotified = '';

  function attached(n) { return !!n && document.documentElement.contains(n); }
  function kidsOf(n) {
    return Array.prototype.filter.call(n.children, function (c) {
      var t = c.tagName;
      return t !== 'SCRIPT' && t !== 'STYLE' && t !== 'LINK' && c.id !== 'mw-back' && c.id !== 'mw-style';
    });
  }
  function big(k) { return k.offsetWidth > 30 && k.offsetHeight > 100; }
  function area(k) { return k.offsetWidth * k.offsetHeight; }

  function findRow() {
    var n = document.getElementById('app') || document.body, guard = 0;
    while (n && guard++ < 14) {
      var ks = kidsOf(n);
      if (!ks.length) break;
      var cs = getComputedStyle(n);
      var rowLike = ((cs.display === 'flex' || cs.display === 'inline-flex') && cs.flexDirection.indexOf('row') === 0) || cs.display === 'grid';
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
    // full-height children count even when squeezed to zero width (flex rows stretch every child to the row height)
    kids0 = kidsOf(row).filter(function (k) { return k.offsetHeight > 100; });
    return kids0.length >= 2;
  }

  function roles() {
    var side = document.getElementById('side'), main = document.getElementById('main');
    var kids = kidsOf(row), list = null, chat = null, hasChat = false;
    kids.forEach(function (k) { if (side && k.contains(side)) list = k; });
    if (main) kids.forEach(function (k) { if (k !== list && k.contains(main)) chat = k; });
    if (chat) hasChat = true;
    if (!chat) {
      kids.forEach(function (k) {
        if (!chat && k !== list && k.querySelector && k.querySelector('footer,[contenteditable="true"],[data-testid="conversation-compose-box-input"]')) { chat = k; hasChat = true; }
      });
    }
    if (!list) list = kids0.slice(0, -1).reduce(function (a, b) { return !a || area(a) >= area(b) ? b : a; }, null);
    if (!chat) {
      var after = kids.filter(function (k) { return k !== list && kids0.indexOf(k) >= 0; });
      chat = after.length ? after[after.length - 1] : null;
    }
    cur = { list: list, chat: chat, others: kids0.filter(function (k) { return k !== list && k !== chat; }), hasChat: hasChat };
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

  function notify(v) {            // only on change: every call can resize the WebView on the Android side
    if (v === lastNotified) return;
    lastNotified = v;
    try { if (window.Android && window.Android.view) window.Android.view(v); } catch (e) {}
  }

  function clearMarks() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-mw],[data-mw-anc],[data-mw-fit],[data-mw-vis],[data-mw-pass],[data-mw-shrink]'), function (e) {
      e.removeAttribute('data-mw'); e.removeAttribute('data-mw-anc'); e.removeAttribute('data-mw-fit');
      e.removeAttribute('data-mw-vis'); e.removeAttribute('data-mw-pass'); e.removeAttribute('data-mw-shrink');
    });
    applied = { view: '', list: null, chat: null, others: '' };
  }

  // The login screen ("Scan to log in" + QR code) must be left completely alone: it is not a chat layout.
  function isLoginPage() {
    if (document.getElementById('side') || document.getElementById('pane-side')) return false;
    if (document.querySelector('canvas[aria-label*="QR" i],[data-ref],[data-testid="qrcode"],[data-testid="qr-code"]')) return true;
    var t = (document.body && document.body.innerText) || '';
    return /scan to log in|log in with phone number|use whatsapp on your computer|link with phone number/i.test(t);
  }

  function apply() {
    if (isLoginPage()) {
      if (applied.view || document.querySelector('[data-mw]')) { clearMarks(); document.documentElement.removeAttribute('data-mw-view'); }
      row = null; kids0 = [];
      if (attached(backBtn)) backBtn.style.display = 'none';
      notify('none');
      return;
    }
    if (!(attached(row) && kids0.length && kids0.every(attached))) {
      clearMarks();
      if (!detect()) { notify('none'); return; }
    }
    if (!roles()) { notify('none'); return; }
    var chatOpen = cur.hasChat, main = document.getElementById('main');
    if (chatOpen && !lastChatOpen) forceList = false;     // a chat was just opened
    lastChatOpen = chatOpen;
    var view = (chatOpen && !forceList) ? 'chat' : 'list';
    var othersSig = cur.others.length;

    var changed = applied.view !== view || applied.list !== cur.list || applied.chat !== cur.chat || applied.others !== othersSig;
    if (changed) {                                          // touch the DOM only when something really changed
      Array.prototype.forEach.call(document.querySelectorAll('[data-mw]'), function (e) { e.removeAttribute('data-mw'); });
      row.setAttribute('data-mw', 'row');
      cur.list.setAttribute('data-mw', 'list');
      cur.chat.setAttribute('data-mw', 'chat');
      cur.others.forEach(function (r) { r.setAttribute('data-mw', 'other'); });
      [row, cur.chat].forEach(function (start) {
        for (var a = start.parentElement; a && a !== document.body; a = a.parentElement) a.setAttribute('data-mw-anc', '1');
      });
      document.documentElement.setAttribute('data-mw-view', view);
      applied = { view: view, list: cur.list, chat: cur.chat, others: othersSig };
    }
    ensureBackBtn().style.display = view === 'chat' ? 'flex' : 'none';
    notify(view);
    // measure/repair once per opened chat (not on every page change)
    if (view === 'chat' && (changed || main !== lastMain)) scheduleRepair();
    lastMain = main;
  }

  // ---------------------------------------------------------------- repair (fit + tap audit)
  function fit() {
    var host = cur.chat;
    if (!attached(host) || overlayOpen() || document.documentElement.getAttribute('data-mw-view') !== 'chat') return;
    var vw = document.documentElement.clientWidth;
    for (var pass = 0; pass < 3; pass++) {
      var els = host.querySelectorAll('*'), changed = false;
      for (var i = 0; i < els.length && i < 4000; i++) {
        var e = els[i];
        if (e.hasAttribute('data-mw-fit') || e.closest(OVERLAY)) continue;
        var r = e.getBoundingClientRect();
        if (r.width > vw + 2 && r.height > 0 && r.left < vw) { e.setAttribute('data-mw-fit', '1'); changed = true; }
      }
      if (!changed) break;
    }
  }

  var lastAudit = { checked: 0, blocked: 0, fixed: 0, notes: [] };
  var CONTROLS = 'button,[role="button"],[tabindex="0"],[data-icon],[contenteditable="true"],input,a[href]';
  function blockedControls(host) {
    var sel = CONTROLS.split(',').map(function (c) { return 'header ' + c + ',footer ' + c; }).join(',');
    var els = host.querySelectorAll(sel), out = [], vw = window.innerWidth, vh = window.innerHeight, n = 0;
    for (var i = 0; i < els.length && n < 300; i++, n++) {
      var el = els[i];
      if (el.closest(OVERLAY)) continue;                    // never judge controls inside menus / the emoji tray
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4) continue;
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none') continue;
      var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      if (cx < 0 || cy < 0 || cx > vw || cy > vh) { out.push({ el: el, top: null }); continue; }
      var t = document.elementFromPoint(cx, cy);
      if (!t || el.contains(t) || t.contains(el) || t.id === 'mw-back' || t.closest(OVERLAY)) continue;
      out.push({ el: el, top: t });
    }
    return { list: out, checked: n };
  }
  function audit() {
    var host = cur.chat;
    if (!attached(host) || overlayOpen() || document.documentElement.getAttribute('data-mw-view') !== 'chat') return;
    var a = blockedControls(host);
    lastAudit = { checked: a.checked, blocked: a.list.length, fixed: 0, notes: [] };
    if (!a.list.length) return;
    a.list.slice(0, 40).forEach(function (b) {
      lastAudit.notes.push(describe(b.el) + (b.top ? '  covered by ' + describe(b.top) : '  off-screen'));
      for (var p = b.el.parentElement; p && p !== host && p.tagName !== 'HEADER' && p.tagName !== 'FOOTER'; p = p.parentElement)
        p.setAttribute('data-mw-vis', '1');
      // text-only siblings on the way up (contact name, subtitle) may shrink to make room for the buttons
      for (var q = b.el; q && q !== host; q = q.parentElement) {
        var par = q.parentElement;
        if (!par) break;
        Array.prototype.forEach.call(par.children, function (sib) {
          if (sib !== q && !sib.querySelector(CONTROLS) && !sib.matches(CONTROLS) && (sib.textContent || '').trim()) sib.setAttribute('data-mw-shrink', '1');
        });
        if (par.tagName === 'HEADER' || par.tagName === 'FOOTER') break;
      }
      var t = b.top;           // an invisible, empty layer lying over a control becomes click-through
      if (t && t !== host && !t.querySelector(CONTROLS) && !(t.textContent || '').trim()) {
        var pos = getComputedStyle(t).position;
        if (pos === 'absolute' || pos === 'fixed') t.setAttribute('data-mw-pass', '1');
      }
    });
    fit();
    var b2 = blockedControls(host);
    lastAudit.fixed = Math.max(0, a.list.length - b2.list.length);
    lastAudit.blocked = b2.list.length;
    b2.list.slice(0, 5).forEach(function (b) { lastAudit.notes.push('STILL BLOCKED ' + describe(b.el) + (b.top ? ' by ' + describe(b.top) : '')); });
  }
  var repairTimers = [];
  function scheduleRepair() {
    repairTimers.forEach(clearTimeout);
    repairTimers = [400, 1500].map(function (ms) { return setTimeout(function () { fit(); audit(); }, ms); });
  }

  // ---------------------------------------------------------------- long-press a message => its action menu
  var lp = null, suppressClickUntil = 0;
  function msgOf(t) { return t && t.closest ? t.closest('[data-id],.message-in,.message-out,[data-testid="msg-container"]') : null; }
  document.addEventListener('touchstart', function (e) {
    if (document.documentElement.getAttribute('data-mw-view') !== 'chat' || e.touches.length !== 1) return;
    if (!msgOf(e.target) || e.target.closest('footer,header,' + OVERLAY)) return;
    var t = e.touches[0];
    lp = { x: t.clientX, y: t.clientY, el: e.target };
    lp.timer = setTimeout(function () {
      if (!lp) return;
      suppressClickUntil = Date.now() + 700;
      lp.el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: lp.x, clientY: lp.y, button: 2, buttons: 2, view: window }));
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
    if (e.isTrusted && msgOf(e.target)) e.preventDefault();
  }, true);

  // ---------------------------------------------------------------- resize handling
  // The soft keyboard and the app bar change only the window HEIGHT. WhatsApp closes its menus and the emoji/attach
  // panels on any resize event, so height-only resizes are not forwarded. Width changes (rotation) pass through.
  var lastW = window.innerWidth;
  window.addEventListener('resize', function (e) {
    if (window.innerWidth === lastW) { e.stopImmediatePropagation(); return; }
    lastW = window.innerWidth;
    clearTimeout(timer); timer = setTimeout(function () { clearMarkFits(); scheduleRepair(); }, 300);
  }, true);
  function clearMarkFits() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-mw-fit]'), function (e) { e.removeAttribute('data-mw-fit'); });
  }

  function schedule() { clearTimeout(timer); timer = setTimeout(apply, 80); }

  function back() {
    if (document.documentElement.getAttribute('data-mw-view') !== 'chat') return false;
    // Escape closes menus, the emoji tray, the attach menu and info panels in WhatsApp Web.
    function snap() { return document.getElementsByTagName('*').length + document.querySelectorAll(OVERLAY).length * 1000; }
    var before = snap(), tgt = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (t) {
      tgt.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
    });
    setTimeout(function () {
      if (snap() === before && document.documentElement.getAttribute('data-mw-view') === 'chat') { forceList = true; apply(); }
    }, 300);
    return true;
  }

  // ---------------------------------------------------------------- bottom-bar navigation
  // Finds WhatsApp's own tab button by what it is called (aria-label, title, test id, icon name or visible text),
  // wherever it lives on the page (the icon strip is hidden in phone mode, but hidden buttons still work).
  var TABS = {
    chats:       { exact: /^(chats?|chat list|all chats)$/, loose: /^chats?\b/, icon: /^(chats?|chat-outline|chats-outline|chats-filled)/, not: /new|archived|select|starred/ },
    updates:     { exact: /^(updates?|status|status updates?)$/, loose: /(updates?|status)/, icon: /^(status|updates?)/, not: /read|unread|typing|delivery/ },
    communities: { exact: /^communit(y|ies)$/, loose: /communit/, icon: /communit/, not: /new communit/ },
    calls:       { exact: /^calls?$/, loose: /^calls?\b/, icon: /^calls?($|-outline|-filled|s)/, not: /voice|video|missed|new call/ },
    settings:    { exact: /^settings?$/, loose: /settings?/, icon: /settings?/, not: /^$/ }
  };
  function labelsOf(el) {
    var out = [];
    ['aria-label', 'title', 'data-testid', 'data-tab', 'name'].forEach(function (a) { var v = el.getAttribute && el.getAttribute(a); if (v) out.push(v.toLowerCase().trim()); });
    var ic = el.querySelector && el.querySelector('[data-icon]');
    if (ic) out.push(('icon:' + ic.getAttribute('data-icon')).toLowerCase());
    if (el.getAttribute && el.getAttribute('data-icon')) out.push(('icon:' + el.getAttribute('data-icon')).toLowerCase());
    var tx = (el.textContent || '').trim().toLowerCase();
    if (tx && tx.length <= 24) out.push(tx);
    return out;
  }
  function clickableOf(el) { return el.closest('button,[role=button],[role=tab],a,[tabindex]') || el; }
  function findTab(tab) {
    var spec = TABS[tab]; if (!spec) return null;
    var cands = document.querySelectorAll('[aria-label],[title],[data-testid],[data-icon],[role=tab],[role=button],[tabindex],button,a[href]'), best = null, bestScore = 0;
    for (var i = 0; i < cands.length && i < 3000; i++) {
      var el = cands[i];
      if ((cur.chat && cur.chat.contains(el)) || el.closest('#pane-side,[data-mw=chat]')) continue;   // not controls of the open conversation / chat rows
      var score = 0;
      labelsOf(el).forEach(function (l) {
        var isIcon = l.indexOf('icon:') === 0, v = isIcon ? l.slice(5) : l;
        if (spec.not.test(v)) return;
        var sc = isIcon ? (spec.icon.test(v) ? 1 : 0) : (spec.exact.test(v) ? 4 : (spec.loose.test(v) ? 2 : 0));
        if (sc > score) score = sc;
      });
      if (!score) continue;
      var host = clickableOf(el);
      if (cur.others.some(function (o) { return o.contains(host); })) score += 1;      // prefer the icon strip / bottom bar
      if (score > bestScore) { bestScore = score; best = host; }
    }
    return best;
  }
  var lastNav = '';
  function nav(tab) {
    var el = findTab(tab);
    lastNav = tab + ' -> ' + (el ? describe(el) + ' [' + labelsOf(el).slice(0, 3).join(' | ') + ']' : 'NOT FOUND');
    if (!el) return false;
    el.click();
    forceList = true;
    setTimeout(apply, 120);
    return true;
  }
  // everything that looks like a tab button in the (hidden) icon strip: used by Layout diagnostics
  function navScan() {
    var out = [];
    cur.others.forEach(function (o) {
      Array.prototype.slice.call(o.querySelectorAll('button,[role=button],[role=tab],a,[tabindex]')).slice(0, 14).forEach(function (e) {
        out.push('  ' + describe(e) + ' [' + labelsOf(e).slice(0, 3).join(' | ') + ']');
      });
    });
    return out;
  }

  function describe(n) {
    if (!n) return '(none)';
    var c = typeof n.className === 'string' ? n.className.split(' ')[0] : '';
    return n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + (c ? '.' + c : '') + ' ' + n.offsetWidth + 'x' + n.offsetHeight;
  }
  function debug() {
    var out = ['viewport ' + window.innerWidth + 'x' + window.innerHeight + ', scrollWidth ' + document.documentElement.scrollWidth,
               'row: ' + describe(row), 'list: ' + describe(cur.list), 'chat: ' + describe(cur.chat),
               'others: ' + (cur.others.map(describe).join(' | ') || '(none)'),
               'view: ' + document.documentElement.getAttribute('data-mw-view') + ', chatOpen: ' + cur.hasChat + ', overlayOpen: ' + overlayOpen(),
               '#side: ' + !!document.getElementById('side') + ', #main: ' + !!document.getElementById('main'), 'row children:'];
    if (row) kidsOf(row).forEach(function (k) { out.push('  ' + describe(k)); });
    out.push('last tab tap: ' + (lastNav || '(none yet)'));
    out.push('icon-strip buttons:');
    navScan().slice(0, 10).forEach(function (l) { out.push(l); });
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
