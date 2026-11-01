/* Small fixes for WhatsApp Web on a phone. Each one touches a single thing and leaves the rest of the page alone:
   1. the attach menu is moved into the visible screen (right side, just above the message bar);
   2. the Send button of the media preview is scrolled into view;
   3. the soft keyboard no longer opens by itself when you tap attach / emoji. */
(function () {
  if (window.__mwFixes) return;
  window.__mwFixes = true;

  // ------------------------------------------------------------------ 1. attach menu placement
  function looksLikeAttachMenu(n) {
    var t = n.textContent || '';
    return t.length < 6000 && /Document/.test(t) && /Photos/.test(t) && /Camera|Audio|Contact|Poll|sticker/i.test(t);
  }
  // the visible card: smallest ancestor of the "Document" item that holds all the items and is card-sized
  function findCard(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), n, item = null;
    while ((n = w.nextNode())) { if (/^\s*Document\s*$/.test(n.nodeValue)) { item = n.parentElement; break; } }
    if (!item) return null;
    for (var p = item, i = 0; p && p !== document.body && i < 14; p = p.parentElement, i++) {
      if (!/Photos/.test(p.textContent || '') || !/Camera|Audio|Contact|Poll|sticker/i.test(p.textContent || '')) continue;
      var r = p.getBoundingClientRect();
      if (r.height >= 150 && r.width >= 120 && r.width <= 560) return p;
    }
    return null;
  }
  // Works whatever way WhatsApp positions the card: measure it, then shift it with the independent CSS "translate".
  function place(card) {
    if (!card || !document.documentElement.contains(card)) return;
    card.style.removeProperty('translate');
    var r = card.getBoundingClientRect(), vw = window.innerWidth, vh = window.innerHeight;
    var f = document.querySelector('footer'), bottomLimit = vh - 72;
    if (f) { var fr = f.getBoundingClientRect(); if (fr.top > 120 && fr.top < vh) bottomLimit = fr.top - 6; }
    var dx = (vw - 8) - r.right, dy = bottomLimit - r.bottom;
    if (r.left + dx < 4) dx = 4 - r.left;                // never off the left edge
    if (r.top + dy < 4) dy = 4 - r.top;                  // never off the top
    card.style.setProperty('translate', Math.round(dx) + 'px ' + Math.round(dy) + 'px', 'important');
    card.style.setProperty('z-index', '2147483000', 'important');
    card.setAttribute('data-mw-attach', '1');
  }

  // ------------------------------------------------------------------ 2. keep the media preview's Send button on screen
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

  // ------------------------------------------------------------------ observer for 1 and 2
  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var n = added[j];
        if (n.nodeType !== 1) continue;
        if (looksLikeAttachMenu(n)) {
          var card = findCard(n);
          if (card && !card.hasAttribute('data-mw-attach')) { [0, 80, 350, 800].forEach(function (ms) { setTimeout(function () { place(card); }, ms); }); }
        }
        revealSend(n);
      }
    }
  }).observe(document.body, { childList: true, subtree: true });

  // ------------------------------------------------------------------ 3. no keyboard unless YOU tapped the message box
  var lastTapAt = 0, lastTapInBox = false;
  function isComposer(el) {
    if (!el || !el.closest) return false;
    var ce = el.closest('[contenteditable="true"],[contenteditable=""],textarea,input');
    if (!ce) return false;
    return !!ce.closest('footer') || /message|type a/i.test((ce.getAttribute('aria-label') || '') + ' ' + (ce.getAttribute('aria-placeholder') || '') + ' ' + (ce.getAttribute('title') || ''));
  }
  ['touchstart', 'pointerdown', 'mousedown'].forEach(function (t) {
    document.addEventListener(t, function (e) { lastTapAt = Date.now(); lastTapInBox = isComposer(e.target); }, true);
  });
  document.addEventListener('focusin', function (e) {
    if (!isComposer(e.target)) return;
    if (lastTapInBox && Date.now() - lastTapAt < 3000) return;                // you tapped the box: keyboard wanted
    if (Date.now() - lastTapAt < 900) {                                        // focus came from tapping something else
      var el = e.target.closest('[contenteditable],textarea,input') || e.target;
      setTimeout(function () { if (document.activeElement === el || el.contains(document.activeElement)) el.blur(); }, 0);
    }
  }, true);
})();
