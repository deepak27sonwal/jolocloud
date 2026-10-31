/* Phone mode for WhatsApp Web: show ONE pane at a time (chat list OR conversation) so it fits the screen. */
(function () {
  if (window.__mw) { window.__mw.refresh(); return; }
  var style = document.createElement('style');
  style.id = 'mw-style';
  document.head.appendChild(style);

  var sideBlock = null, rightBlock = null, forceList = false, lastChatOpen = false, timer = null;
  var backBtn = null;

  function attached(n) { return n && document.documentElement.contains(n); }

  // The two panes are the sibling blocks that contain #side (chat list) and the conversation/intro area.
  function findBlocks() {
    var side = document.getElementById('side');
    if (!side) return false;
    var n = side;
    while (n.parentElement && n.parentElement !== document.body) {
      var p = n.parentElement;
      var sibs = Array.prototype.filter.call(p.children, function (c) {
        return c !== n && c.offsetWidth > 150 && c.offsetHeight > 150;
      });
      if (sibs.length >= 1) { sideBlock = n; rightBlock = sibs[0]; return true; }
      n = p;
    }
    return false;
  }

  function ensureBackBtn() {
    if (backBtn && attached(backBtn)) return backBtn;
    backBtn = document.createElement('div');
    backBtn.id = 'mw-back';
    backBtn.textContent = '‹';
    backBtn.addEventListener('click', function (e) { e.stopPropagation(); back(); });
    document.body.appendChild(backBtn);
    return backBtn;
  }

  function apply() {
    if (!(attached(sideBlock) && attached(rightBlock))) {
      style.textContent = '';                 // measure the original layout again
      sideBlock = rightBlock = null;
      if (!findBlocks()) return;
    }
    var chatOpen = !!document.getElementById('main');
    if (chatOpen && !lastChatOpen) forceList = false;   // a chat was just opened
    lastChatOpen = chatOpen;
    var showChat = chatOpen && !forceList;

    sideBlock.setAttribute('data-mw', 'side');
    rightBlock.setAttribute('data-mw', 'right');
    // every ancestor up to <body> must be allowed to shrink to the phone width
    for (var a = sideBlock.parentElement; a && a !== document.body; a = a.parentElement) a.setAttribute('data-mw-anc', '1');
    document.documentElement.setAttribute('data-mw-view', showChat ? 'chat' : 'list');
    ensureBackBtn().style.display = showChat ? 'flex' : 'none';

    style.textContent =
      'html,body{min-width:0!important;width:100%!important;max-width:100vw!important;overflow-x:hidden!important}' +
      '[data-mw-anc]{min-width:0!important;max-width:100vw!important;width:100%!important}' +
      '[data-mw]{width:100%!important;max-width:100vw!important;min-width:0!important;flex:1 1 100%!important}' +
      'html[data-mw-view=chat] [data-mw=side]{display:none!important}' +
      'html[data-mw-view=list] [data-mw=right]{display:none!important}' +
      '#mw-back{position:fixed;left:0;top:10px;width:30px;height:46px;z-index:2147483647;display:none;' +
      'align-items:center;justify-content:center;font:700 30px/1 sans-serif;color:#fff;' +
      'background:rgba(0,0,0,.45);border-radius:0 23px 23px 0}';
  }

  function schedule() { clearTimeout(timer); timer = setTimeout(apply, 80); }

  function back() {
    var v = document.documentElement.getAttribute('data-mw-view');
    if (v === 'chat') { forceList = true; apply(); return true; }
    return false;
  }

  new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
  // tapping a chat in the list always switches to the conversation, even if it is already the open one
  document.addEventListener('click', function (e) {
    if (attached(sideBlock) && sideBlock.contains(e.target) && e.target.closest && e.target.closest('#pane-side')) {
      forceList = false;
      setTimeout(apply, 60);
    }
  }, true);

  window.__mw = { refresh: apply, back: back };
  apply();
})();
