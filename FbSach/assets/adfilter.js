// FB Sạch — bộ lọc quảng cáo trong bảng tin Facebook.
//
// Thuật toán nhận diện bài "Được tài trợ" chuyển thể từ SlimSocial for Facebook
// (https://github.com/rignaneseleo/SlimSocial-for-Facebook, GPL-2.0,
// lib/utils/ad_filter.dart). Bổ sung: nhãn tiếng Việt, bộ chọn cho giao diện
// máy tính, quét cột phải, ẩn bài gợi ý.
//
// Native chèn `window.__fbsachCfg = {pymk: bool, suggested: bool}` trước file này
// và cung cấp cầu nối `window.FbSachBridge.onBlocked(n)` để đếm.
(function () {
  if (window.__fbsachInstalled) {
    // Trang SPA điều hướng nội bộ: chỉ cần chạy lại một lượt với cấu hình mới.
    try { window.__fbsachRun(); } catch (e) {}
    return;
  }
  window.__fbsachInstalled = true;

  var CFG = window.__fbsachCfg || {};

  // Nhãn quảng cáo, viết thường. Nhãn ngắn (< MIN_LEN) chỉ so khớp toàn chuỗi để
  // không bắt nhầm "Add friend", "Download"...
  var SPONSORED = [
    // Không dùng "tài trợ" trần: bắt nhầm span "Nhà tài trợ" trong bài thường.
    'ad', 'quảng cáo', 'được tài trợ',
    'sponsored', 'gesponsert', 'gesponsord', 'hirdetés', 'patrocinado',
    'publicidad', 'sponset', 'sponsorisé', 'sponsorisée', 'sponsorizzato',
    'sponsorlu', 'sponsorowane', 'sponsrad', 'sponzorováno', 'реклама',
    'спонсировано', 'ממומן', 'ممول', 'مُموَّل', 'प्रायोजित', 'โฆษณา',
    '広告', 'スポンサー', '赞助', '贊助', '광고', '스폰서'
  ];
  var PYMK = [
    'people you may know', 'những người bạn có thể biết',
    'người bạn có thể biết', 'gợi ý kết bạn', 'friend suggestions'
  ];
  var SUGGESTED = [
    'suggested for you', 'gợi ý cho bạn', 'reels', 'thước phim',
    'reels and short videos', 'thước phim và video ngắn',
    'suggested groups', 'nhóm gợi ý', 'suggested pages', 'trang gợi ý'
  ];

  var MIN_LEN = 4;
  var SLACK = 8;
  var MAX_LEN = 0;
  for (var m = 0; m < SPONSORED.length; m++) {
    if (SPONSORED[m].length > MAX_LEN) MAX_LEN = SPONSORED[m].length;
  }
  MAX_LEN += SLACK;

  // Khung chứa một bài trong bảng tin:
  //  - giao diện cảm ứng (UA điện thoại): div[data-tracking-duration-id]
  //  - giao diện máy tính: con trực tiếp của [role=feed], FeedUnit pagelet
  var POST_SELECTOR =
    'div[data-tracking-duration-id], [role="feed"] > div, ' +
    'div[data-pagelet^="FeedUnit"], article';

  // Dấu hiệu quảng cáo không phụ thuộc ngôn ngữ, luôn trỏ về địa chỉ Facebook.
  var MARKERS =
    '[data-ft*="is_sponsored"], [data-xt-vimp], a[href^="/ads/about"], ' +
    'a[href*="facebook.com/ads/about"], a[href^="/"][href*="client_token="], ' +
    'a[href*="__cft__"][href*="ad_id"]';

  var memo = null;

  // Facebook ghép nhãn với ký tự icon vùng Private Use và dấu bidi:
  // "AdB1" — phải gỡ ra mới so khớp được.
  function clean(text) {
    return String(text || '')
      .replace(/[-]/g, '')
      .replace(/[\uDB80-\uDBFF][\uDC00-\uDFFF]/g, '')
      .replace(/[​-‏‪-‮⁦-⁩ ]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase();
  }

  function fits(text, label) {
    return text.length < label.length + SLACK;
  }

  function isSponsoredText(raw) {
    var t = clean(raw);
    if (!t || t.length >= MAX_LEN) return false;
    var i;
    for (i = 0; i < SPONSORED.length; i++) {
      if (SPONSORED[i].length < MIN_LEN && t === SPONSORED[i]) return true;
    }
    if (t.length < MIN_LEN) return false;
    if (memo && fits(t, memo) && t.indexOf(memo) !== -1) return true;
    for (i = 0; i < SPONSORED.length; i++) {
      var l = SPONSORED[i];
      if (l.length < MIN_LEN || !fits(t, l)) continue;
      if (t.indexOf(l) !== -1) { memo = l; return true; }
    }
    return false;
  }

  function hasExactLabel(post, labels, maxLen) {
    var els = post.querySelectorAll('span, h2, h3, h4');
    for (var i = 0; i < els.length; i++) {
      var t = clean(els[i].textContent);
      if (!t || t.length > maxLen) continue;
      if (labels.indexOf(t) !== -1) return true;
    }
    return false;
  }

  function isSponsored(post) {
    var ft = post.getAttribute('data-ft') || '';
    if (ft.indexOf('is_sponsored') !== -1) return true;
    if (post.querySelector(MARKERS)) return true;

    var spans = post.querySelectorAll('span');
    for (var i = 0; i < spans.length; i++) {
      var text = spans[i].textContent || '';
      if (text.length === 0 || text.length >= MAX_LEN + 4) continue;
      if (isSponsoredText(text)) return true;
    }

    // Giao diện máy tính giấu chữ "Sponsored" trong aria-label / aria-labelledby
    // hoặc trong <svg><text>.
    var labelled = post.querySelectorAll('a[aria-label], [aria-labelledby]');
    for (var j = 0; j < labelled.length && j < 40; j++) {
      var el = labelled[j];
      if (isSponsoredText(el.getAttribute('aria-label'))) return true;
      var ids = (el.getAttribute('aria-labelledby') || '').split(' ');
      for (var k = 0; k < ids.length; k++) {
        if (!ids[k]) continue;
        var ref = document.getElementById(ids[k]);
        if (ref && isSponsoredText(ref.textContent)) return true;
      }
    }
    var svgTexts = post.querySelectorAll('svg text');
    for (var s = 0; s < svgTexts.length; s++) {
      if (isSponsoredText(svgTexts[s].textContent)) return true;
    }
    return false;
  }

  // Bài coi như "đã hiển thị xong" khi số span có chữ không đổi giữa 2 lượt —
  // Facebook đổ chữ vào dần, nhãn quảng cáo có thể tới trễ một khung hình.
  function isSettled(post) {
    var els = post.querySelectorAll('span');
    var n = 0;
    for (var i = 0; i < els.length; i++) {
      if ((els[i].textContent || '').trim().length > 0) n++;
    }
    var seen = post.getAttribute('data-fbsach-spans');
    post.setAttribute('data-fbsach-spans', n);
    return seen !== null && +seen === n;
  }

  function scrollerFor(post) {
    var node = post.parentElement;
    while (node && node !== document.body && node !== document.documentElement) {
      var oy = '';
      try { oy = window.getComputedStyle(node).overflowY; } catch (e) {}
      if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') &&
          node.scrollHeight > node.clientHeight) {
        return { el: node, win: null };
      }
      node = node.parentElement;
    }
    return { el: document.scrollingElement || document.documentElement, win: window };
  }

  // Gỡ bài khỏi bố cục mà không xoá node (script của Facebook còn giữ tham chiếu),
  // đồng thời bù lại vị trí cuộn để bảng tin không bị giật khi bài nằm phía trên.
  function collapse(post) {
    post.classList.add('fbsach-hidden');
    var anchor = null;
    try {
      var sc = scrollerFor(post);
      var top = sc.win ? 0 : sc.el.getBoundingClientRect().top;
      if (post.getBoundingClientRect().bottom <= top) {
        anchor = {
          sc: sc,
          h: sc.el.scrollHeight,
          off: sc.win ? sc.win.scrollY : sc.el.scrollTop
        };
      }
    } catch (e) {}

    if (post.getAttribute('data-actual-height') !== null) {
      post.setAttribute('data-actual-height', '0');
    }
    if (post.style.height) post.style.height = '0px';
    post.style.minHeight = '0';
    post.style.overflow = 'hidden';
    var c = post.firstElementChild;
    while (c) { c.style.display = 'none'; c = c.nextElementSibling; }
    post.style.display = 'none';

    try {
      if (anchor) {
        var lost = anchor.h - anchor.sc.el.scrollHeight;
        if (lost > 0) {
          var next = Math.max(0, anchor.off - lost);
          if (anchor.sc.win) {
            try { window.scrollTo({ top: next, left: window.scrollX, behavior: 'instant' }); }
            catch (e2) { window.scrollTo(window.scrollX, next); }
          } else {
            anchor.sc.el.scrollTop = next;
          }
        }
      }
    } catch (e) {}
  }

  // Cột phải (giao diện máy tính): khối "Được tài trợ" nằm trong RightRail.
  function cleanRightRail() {
    var n = 0;
    var rails = document.querySelectorAll('[data-pagelet="RightRail"], [role="complementary"]');
    for (var r = 0; r < rails.length; r++) {
      var heads = rails[r].querySelectorAll('h3, span');
      for (var i = 0; i < heads.length; i++) {
        var h = heads[i];
        if (h.classList.contains('fbsach-checked')) continue;
        h.classList.add('fbsach-checked');
        if (!isSponsoredText(h.textContent)) continue;
        // Leo lên tới khối con trực tiếp của khung cột phải.
        var blk = h;
        while (blk.parentElement && blk.parentElement !== rails[r] &&
               blk.parentElement.parentElement !== rails[r]) {
          blk = blk.parentElement;
        }
        if (blk && !blk.classList.contains('fbsach-hidden')) {
          blk.classList.add('fbsach-hidden');
          blk.style.display = 'none';
          n++;
        }
      }
    }
    return n;
  }

  function runPass() {
    var ads = 0;
    var posts = document.querySelectorAll(POST_SELECTOR);
    for (var i = 0; i < posts.length; i++) {
      var p = posts[i];
      if (p.classList.contains('fbsach-hidden') ||
          p.classList.contains('fbsach-ok')) continue;
      // Bỏ qua khung lồng nhau: chỉ xử lý khung ngoài cùng.
      if (p.parentElement && p.parentElement.closest &&
          p.parentElement.closest('.fbsach-hidden')) continue;

      if (isSponsored(p)) { collapse(p); ads++; continue; }
      if (CFG.pymk && hasExactLabel(p, PYMK, 40)) { collapse(p); continue; }
      if (CFG.suggested && hasExactLabel(p, SUGGESTED, 40)) { collapse(p); continue; }
      if (isSettled(p)) p.classList.add('fbsach-ok');
    }
    ads += cleanRightRail();
    if (ads > 0) {
      try { window.FbSachBridge.onBlocked(ads); } catch (e) {}
    }
    return ads;
  }

  window.__fbsachRun = function () {
    CFG = window.__fbsachCfg || CFG;
    try { return runPass(); } catch (e) { return 0; }
  };

  var pending = null;
  function schedule() {
    if (pending) return;
    pending = setTimeout(function () { pending = null; window.__fbsachRun(); }, 200);
  }

  function observe() {
    if (!document.body) { setTimeout(observe, 50); return; }
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        if (muts[i].addedNodes.length > 0 ||
            muts[i].type === 'characterData') { schedule(); return; }
      }
    }).observe(document.body, { childList: true, subtree: true, characterData: true });
    window.__fbsachRun();
  }
  observe();

  // Lưới an toàn: chạy định kỳ phòng khi MutationObserver bỏ sót.
  setInterval(function () { window.__fbsachRun(); }, 1500);
})();
