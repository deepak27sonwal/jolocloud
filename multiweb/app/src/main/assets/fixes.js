/* Small fixes for WhatsApp Web on a phone. Each one touches a single thing and leaves the rest of the page alone:
   1. the Send button of the media preview is scrolled into view;
   2. the soft keyboard no longer opens by itself when you tap attach / emoji.
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
})();
