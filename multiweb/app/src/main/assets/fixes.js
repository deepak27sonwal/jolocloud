/* Small fixes for WhatsApp Web on a phone. Each one touches a single thing and leaves the rest of the page alone:
   1. the Send button of the media preview is scrolled into view;
   2. the soft keyboard no longer opens by itself when you tap attach / emoji;
   3. an open chat is shown alone at full screen width (no chat list overlapping it); closed chats leave the page untouched.
   The attach (paperclip) menu is NOT touched: it opens where WhatsApp puts it. */
(function () {
  if (window.__mwFixes) return;
  window.__mwFixes = true;

  // ------------------------------------------------------------------ 1. keep the media preview's Send button on screen
  var SEND = '[data-icon="send"],[aria-label="Send"],[data-testid="send"],[data-icon="wds-ic-send-filled"]';
  function ensureVisible(el) {
    var r = el.getBoundingClientRect(), vw = window.innerWidth, vh = window.innerHeight;
    if (r.width === 0 && r.height === 0) return;
    var dx = 0, dy = 0;
    if (r.right > vw - 8) dx = r.right - vw + 24;
    else if (r.left < 8) dx = r.left - 24;
    if (r.bottom > vh - 8) dy = r.bottom - vh + 24;
    if (dx || dy) window.scrollBy(dx, dy);
  }
  function revealSend(root) {
    var els = root.querySelectorAll ? root.querySelectorAll(SEND) : [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i].closest('button,[role=button]') || els[i];
      (function (e) { [0, 250, 700].forEach(function (ms) { setTimeout(function () { ensureVisible(e); }, ms); }); })(el);
    }
  }

  // ------------------------------------------------------------------ observer
  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var n = added[j];
        if (n.nodeType !== 1) continue;
        revealSend(n);
      }
    }
  }).observe(document.body, { childList: true, subtree: true });

  // ------------------------------------------------------------------ 2. keyboard only when YOU tap the message box
  // WhatsApp focuses the message box on its own (opening a chat, after attach / emoji / send), and Android then opens the
  // keyboard. The box is therefore marked inputmode="none" (focus works, but no on-screen keyboard) and switched to
  // inputmode="text" only while a finger is on it.
  var lastTapAt = 0, lastTapInBox = false;
  function composerOf(el) {
    if (!el || !el.closest) return null;
    var ce = el.closest('[contenteditable="true"],[contenteditable=""],textarea');
    if (!ce) return null;
    if (ce.closest('footer')) return ce;
    var lab = (ce.getAttribute('aria-label') || '') + ' ' + (ce.getAttribute('aria-placeholder') || '') + ' ' + (ce.getAttribute('title') || '');
    return /message|type a/i.test(lab) ? ce : null;
  }
  function protect(ce) { if (ce && ce.getAttribute('inputmode') !== 'none') ce.setAttribute('inputmode', 'none'); }
  function allow(ce) { if (ce && ce.getAttribute('inputmode') !== 'text') ce.setAttribute('inputmode', 'text'); }
  function protectAll(root) {
    var list = (root.querySelectorAll ? root.querySelectorAll('footer [contenteditable],footer textarea,[contenteditable][aria-label*="essage" i]') : []);
    for (var i = 0; i < list.length; i++) protect(list[i]);
    if (root.nodeType === 1 && composerOf(root)) protect(composerOf(root));
  }
  ['touchstart', 'pointerdown', 'mousedown'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      lastTapAt = Date.now();
      var ce = composerOf(e.target);
      lastTapInBox = !!ce;
      if (ce) allow(ce);                                  // your tap: the keyboard is wanted
    }, true);
  });
  document.addEventListener('focusin', function (e) {
    var ce = composerOf(e.target);
    if (ce && !(lastTapInBox && Date.now() - lastTapAt < 3000)) protect(ce);   // focus not caused by your tap: no keyboard
  }, true);
  document.addEventListener('focusout', function (e) { protect(composerOf(e.target)); }, true);
  protectAll(document);
  new MutationObserver(function (records) {                // composers that WhatsApp creates later
    for (var i = 0; i < records.length; i++) for (var j = 0; j < records[i].addedNodes.length; j++) {
      var n = records[i].addedNodes[j]; if (n.nodeType === 1) protectAll(n);
    }
  }).observe(document.body, { childList: true, subtree: true });

  // ------------------------------------------------------------------ 3. an open chat fills the screen (no list overlapping it)
  // Only while a chat is open: the chat list / icon strip next to it are hidden and the conversation gets the full width.
  // Closing the chat removes every change again, so Calls, Status, Communities... stay exactly as WhatsApp draws them.
  var OVERLAY = '[role="dialog"],[role="menu"],[role="listbox"],[aria-modal="true"],[data-animate-dropdown-item],[data-animate-modal-popup],[data-testid*="emoji"],[data-testid*="attach"],[data-testid*="popup"],[data-testid*="menu"]';
  var cf = { on: true, kid: null, anchor: null, active: false, closedFor: null, timers: [] };
  var cfStyle = document.createElement('style');
  cfStyle.textContent =
    'html[data-mw-chatfit] [data-mw-hide]{display:none!important}' +
    'html[data-mw-chatfit] [data-mw-solo]{flex:1 1 100%!important;width:100%!important;min-width:0!important;max-width:100vw!important}' +
    'html[data-mw-chatfit] [data-mw-anc],html[data-mw-chatfit],html[data-mw-chatfit] body,html[data-mw-chatfit] #app{min-width:0!important;max-width:100vw!important;width:100%!important;overflow-x:hidden!important}' +
    'html[data-mw-chatfit] [data-mw-fit]{max-width:100%!important;min-width:0!important}';
  document.head.appendChild(cfStyle);

  function cfAttached(n) { return !!n && document.documentElement.contains(n); }
  function overlayOpen() { return !!document.querySelector(OVERLAY); }
  function chatAnchor() { return document.getElementById('main') || document.querySelector('footer [contenteditable="true"]'); }
  // the child of the page's main row that holds the conversation (its siblings are the chat list / icon strip)
  function findKid(anchor) {
    for (var n = anchor; n && n !== document.body; n = n.parentElement) {
      var p = n.parentElement; if (!p) break;
      var cs = getComputedStyle(p);
      var rowLike = ((cs.display === 'flex' || cs.display === 'inline-flex') && cs.flexDirection.indexOf('row') === 0) || cs.display === 'grid';
      if (!rowLike) continue;                                       // panes sit side by side in a horizontal row
      var sibs = Array.prototype.filter.call(p.children, function (c) { return c !== n && c.tagName !== 'SCRIPT' && c.tagName !== 'STYLE' && c.offsetHeight > 100 && c.offsetWidth > 0; });
      if (sibs.length) return n;
    }
    return null;
  }
  function cfDeactivate() {
    cf.timers.forEach(clearTimeout); cf.timers = [];
    document.documentElement.removeAttribute('data-mw-chatfit');
    Array.prototype.forEach.call(document.querySelectorAll('[data-mw-solo],[data-mw-hide],[data-mw-anc],[data-mw-fit]'), function (e) {
      e.removeAttribute('data-mw-solo'); e.removeAttribute('data-mw-hide'); e.removeAttribute('data-mw-anc'); e.removeAttribute('data-mw-fit');
    });
    cf.active = false; cf.kid = null; cf.anchor = null;
  }
  function cfFit() {                                      // squeeze anything inside the conversation that is still wider than the screen
    if (!cf.active || !cfAttached(cf.kid) || overlayOpen()) return;
    var vw = document.documentElement.clientWidth;
    for (var pass = 0; pass < 3; pass++) {
      var els = cf.kid.querySelectorAll('*'), changed = false;
      for (var i = 0; i < els.length && i < 4000; i++) {
        var e = els[i]; if (e.hasAttribute('data-mw-fit') || e.closest(OVERLAY)) continue;
        var r = e.getBoundingClientRect();
        if (r.width > vw + 2 && r.height > 0 && r.left < vw) { e.setAttribute('data-mw-fit', '1'); changed = true; }
      }
      if (!changed) break;
    }
  }
  function cfApply() {
    var anchor = chatAnchor();
    if (!cf.on || !anchor) { if (cf.active) cfDeactivate(); if (!anchor) cf.closedFor = null; return; }
    if (cf.closedFor === anchor) return;                   // you closed this chat with Back: leave it alone
    if (cf.active && cf.anchor === anchor && cfAttached(cf.kid)) return;     // already set up: nothing to do
    cfDeactivate();                                        // measure the ORIGINAL layout
    var kid = findKid(anchor); if (!kid) return;
    kid.setAttribute('data-mw-solo', '1');
    Array.prototype.forEach.call(kid.parentElement.children, function (c) {
      if (c !== kid && c.tagName !== 'SCRIPT' && c.tagName !== 'STYLE') c.setAttribute('data-mw-hide', '1');
    });
    for (var a = kid.parentElement; a && a !== document.body; a = a.parentElement) a.setAttribute('data-mw-anc', '1');
    document.documentElement.setAttribute('data-mw-chatfit', '1');
    cf.active = true; cf.kid = kid; cf.anchor = anchor;
    window.scrollTo(0, 0);
    cf.timers = [400, 1500].map(function (ms) { return setTimeout(cfFit, ms); });
  }
  var cfTimer = null;
  new MutationObserver(function () { clearTimeout(cfTimer); cfTimer = setTimeout(cfApply, 100); }).observe(document.body, { childList: true, subtree: true });
  cfApply();

  // Back: close an open menu / panel first, then the chat itself
  function cfBack() {
    if (!cf.active) return false;
    function snap() { return document.getElementsByTagName('*').length + document.querySelectorAll(OVERLAY).length * 1000; }
    var before = snap(), tgt = document.activeElement || document.body;
    ['keydown', 'keyup'].forEach(function (t) {
      tgt.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
    });
    setTimeout(function () {
      if (cf.active && snap() === before) { cf.closedFor = cf.anchor; cfDeactivate(); }     // nothing closed: show the list again
    }, 320);
    return true;
  }
  window.__mwFix = {
    back: cfBack,
    setOn: function (on) { cf.on = !!on; cf.closedFor = null; if (!cf.on && cf.active) cfDeactivate(); else cfApply(); return cf.on; },
    state: function () { return 'chatfit on=' + cf.on + ' active=' + cf.active + ' kid=' + (cf.kid ? cf.kid.tagName + '.' + (cf.kid.className || '').toString().slice(0, 20) : 'none'); }
  };
})();
