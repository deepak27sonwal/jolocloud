/* Keeps WhatsApp Web's attach menu (Document, Photos & videos, Camera, ...) inside the visible screen:
   at the right side, just above the message bar. It only touches that one popup, nothing else on the page. */
(function () {
  if (window.__mwAttach) return;
  window.__mwAttach = true;

  function looksLikeAttachMenu(n) {
    var t = n.textContent || '';
    return t.length < 4000 && /Document/.test(t) && /Photos/.test(t) && /Camera|Audio|Contact|Poll|sticker/i.test(t);
  }

  // the floating card that holds the items: nearest absolutely/fixed positioned ancestor of the "Document" item
  function findCard(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), n, item = null;
    while ((n = w.nextNode())) { if (/^\s*Document\s*$/.test(n.nodeValue)) { item = n.parentElement; break; } }
    if (!item) return null;
    for (var p = item, i = 0; p && p !== document.body && i < 12; p = p.parentElement, i++) {
      if (!/Photos/.test(p.textContent || '')) continue;                 // keep climbing until it holds the other items too
      var pos = getComputedStyle(p).position;
      if (pos === 'absolute' || pos === 'fixed') return p;
    }
    return null;
  }

  function place(card) {
    if (!card || !document.documentElement.contains(card)) return;
    var bottom = 72, f = document.querySelector('footer');
    if (f) { var r = f.getBoundingClientRect(); if (r.top > 0) bottom = Math.round(window.innerHeight - r.top + 6); }
    var s = card.style;
    [['position', 'fixed'], ['right', '8px'], ['left', 'auto'], ['top', 'auto'], ['bottom', bottom + 'px'],
     ['transform', 'none'], ['margin', '0'], ['max-width', 'calc(100vw - 16px)'],
     ['max-height', 'calc(100vh - ' + (bottom + 70) + 'px)'], ['overflow-y', 'auto'], ['z-index', '2147483000']
    ].forEach(function (kv) { s.setProperty(kv[0], kv[1], 'important'); });
    card.setAttribute('data-mw-attach', '1');
  }

  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var added = records[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var n = added[j];
        if (n.nodeType !== 1 || !looksLikeAttachMenu(n)) continue;
        var card = findCard(n);
        if (card && !card.hasAttribute('data-mw-attach')) {
          place(card);
          setTimeout(function () { place(card); }, 60);     // WhatsApp may set its own position right after mounting
          setTimeout(function () { place(card); }, 350);
        }
      }
    }
  }).observe(document.body, { childList: true, subtree: true });
})();
