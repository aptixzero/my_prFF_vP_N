/* Professor VPN — Admin Console (v3.0)
 * Fully client-side. No credentials live in this source: only SHA-256 hashes
 * of the username/password are stored, so reading this file never reveals
 * them. The GitHub token is entered at runtime and kept ONLY in the browser's
 * localStorage — never committed.
 *
 * Publishing model:
 *   - app_config.json is written to prfgame/prf-VPN @ adminpanel/app_config.json
 *   - The Android app + the download website both read that file (with
 *     cache-busting) and pick up changes live, no restart required.
 *   - Uploaded images/GIFs are committed as binary blobs into
 *     adminpanel/media/ and referenced by their raw.githubusercontent URL.
 */
(function () {
  "use strict";

  // Login is verified by comparing SHA-256 of typed input against these
  // digests. The plaintext credentials are NOT present anywhere in source.
  var USER_HASH = "9e7b69b83de60d8771bad1cceace73a97986da02e74fde20f913ea96be71bf6c";
  var PASS_HASH = "c751b1388c0f70e1a104d47f945bd888cdde69fd414b48fa3c4be5b7d73e9e7d";

  var LS_AUTH = "pv_auth";
  var LS_TOKEN = "pv_gh_token";
  var DEFAULT_CONFIG_URL =
    "https://raw.githubusercontent.com/prfgame/prf-VPN/main/adminpanel/app_config.json";

  var $ = function (id) { return document.getElementById(id); };
  var val = function (id) { var e = $(id); return e ? e.value : ""; };
  var trimv = function (id) { return (val(id) || "").trim(); };
  var checked = function (id) { var e = $(id); return e ? e.checked : false; };

  async function sha256(text) {
    var buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
    return Array.from(new Uint8Array(buf))
      .map(function (b) { return b.toString(16).padStart(2, "0"); })
      .join("");
  }

  function setStatus(msg, cls) {
    var s = $("status");
    if (!s) return;
    s.textContent = msg;
    s.className = "status" + (cls ? " " + cls : "");
  }

  // ---------------------------------------------------------------- model
  function defaultModel() {
    return {
      version: 1,
      // App-side banner (shown only inside the Android app)
      appBanner: {
        enabled: true,
        title: "محل تبلیغ شما",
        subtitle: "جهت ثبت تبلیغ با ما در ارتباط باشید",
        bgColor: "#0B0B0F",
        textColor: "#F4F2FB",
        imageUrl: "",
        mediaVisible: true,
        action: "contact",
        actionUrl: ""
      },
      // Website-side banner (shown only on the download page)
      websiteBanner: {
        enabled: true,
        title: "محل تبلیغ شما",
        subtitle: "جهت ثبت تبلیغ با ما در ارتباط باشید",
        imageUrl: "",
        mediaVisible: true,
        action: "contact",
        actionUrl: ""
      },
      appLogo: { url: "" },
      websiteLogo: { url: "" },
      sponsor: {
        enabled: true,
        heading: "",
        note: "",
        items: []   // [{ id, title, text, imageUrl, link, protocol }]
      },
      contact: {
        text: "جهت ثبت تبلیغات و استعلام قیمت بنر به آیدی زیر در تلگرام پیام دهید:",
        telegramId: "@mx_pr",
        telegramUrl: "https://t.me/mx_pr",
        btnCopy: "کپی آیدی",
        btnSend: "ارسال پیام"
      },
      // "In-App Telegram Link" — the channel the home-screen Telegram icon opens.
      inAppTelegramUrl: "",
      // Legacy block — mirror of appBanner so old app builds keep working.
      ad: {
        enabled: true,
        title: "محل تبلیغ شما",
        subtitle: "جهت ثبت تبلیغ با ما در ارتباط باشید",
        bgColor: "#0B0B0F",
        textColor: "#F4F2FB",
        imageUrl: "",
        action: "contact",
        actionUrl: ""
      }
    };
  }

  // ---------------------------------------------------------------- read form
  function readForm() {
    var appBanner = {
      enabled: checked("ab_enabled"),
      title: trimv("ab_title"),
      subtitle: trimv("ab_subtitle"),
      bgColor: (trimv("ab_bg_hex") || "#0B0B0F"),
      textColor: (trimv("ab_text_hex") || "#F4F2FB"),
      imageUrl: trimv("ab_image"),
      mediaVisible: checked("ab_media_visible"),
      action: val("ab_action") || "contact",
      actionUrl: trimv("ab_action_url")
    };
    var websiteBanner = {
      enabled: checked("wb_enabled"),
      title: trimv("wb_title"),
      subtitle: trimv("wb_subtitle"),
      imageUrl: trimv("wb_image"),
      mediaVisible: checked("wb_media_visible"),
      action: val("wb_action") || "contact",
      actionUrl: trimv("wb_action_url")
    };
    return {
      version: (window.__ver || 0) + 1,
      // Monotonic publish timestamp — lets every live consumer (app + site)
      // detect a fresh publish instantly even if the CDN serves a cached copy.
      ts: Date.now(),
      // §5 — human-readable last-update stamp shown in the panel after Save.
      lastUpdatedAt: Date.now(),
      // §5 — latest published APK version, auto-filled from the version field so
      // the download page / app can reference the current release.
      latestApkVersion: (trimv("latest_apk_version") || "3.8"),
      appBanner: appBanner,
      websiteBanner: websiteBanner,
      appLogo: { url: trimv("app_logo") },
      websiteLogo: { url: trimv("web_logo") },
      // In-app Telegram link (home-screen Telegram icon). Published both as a
      // flat field and a nested object so any app build can read it.
      inAppTelegramUrl: trimv("c_inapp_tg"),
      inAppTelegram: { url: trimv("c_inapp_tg") },
      sponsor: {
        enabled: checked("sp_enabled"),
        heading: trimv("sp_heading"),
        note: trimv("sp_note"),
        items: readSponsorItems()
      },
      contact: {
        text: trimv("c_text"),
        telegramId: trimv("c_tg_id"),
        telegramUrl: trimv("c_tg_url"),
        btnCopy: trimv("c_btn_copy") || "کپی آیدی",
        btnSend: trimv("c_btn_send") || "ارسال پیام"
      },
      // Legacy mirror of appBanner for backward compatibility.
      ad: {
        enabled: appBanner.enabled,
        title: appBanner.title,
        subtitle: appBanner.subtitle,
        bgColor: appBanner.bgColor,
        textColor: appBanner.textColor,
        imageUrl: appBanner.mediaVisible ? appBanner.imageUrl : "",
        action: appBanner.action,
        actionUrl: appBanner.actionUrl
      }
    };
  }

  // ---------------------------------------------------------------- write form
  function setVal(id, v) { var e = $(id); if (e) e.value = v == null ? "" : v; }
  function setChk(id, v) { var e = $(id); if (e) e.checked = !!v; }

  function writeForm(m) {
    window.__ver = m.version || 0;
    var verBadge = $("ver-badge");
    if (verBadge) verBadge.textContent = "v" + (m.version || 0);

    var ab = m.appBanner || {};
    setChk("ab_enabled", ab.enabled !== false);
    setVal("ab_title", ab.title);
    setVal("ab_subtitle", ab.subtitle);
    setVal("ab_bg_hex", ab.bgColor || "#0B0B0F");
    setVal("ab_bg_color", toColorInput(ab.bgColor || "#0B0B0F"));
    setVal("ab_text_hex", ab.textColor || "#F4F2FB");
    setVal("ab_text_color", toColorInput(ab.textColor || "#F4F2FB"));
    setVal("ab_image", ab.imageUrl);
    setChk("ab_media_visible", ab.mediaVisible !== false);
    setVal("ab_action", ab.action || "contact");
    setVal("ab_action_url", ab.actionUrl);

    var wb = m.websiteBanner || {};
    setChk("wb_enabled", wb.enabled !== false);
    setVal("wb_title", wb.title);
    setVal("wb_subtitle", wb.subtitle);
    setVal("wb_image", wb.imageUrl);
    setChk("wb_media_visible", wb.mediaVisible !== false);
    setVal("wb_action", wb.action || "contact");
    setVal("wb_action_url", wb.actionUrl);

    setVal("app_logo", (m.appLogo || {}).url);
    setVal("web_logo", (m.websiteLogo || {}).url);

    var sp = m.sponsor || {};
    setChk("sp_enabled", sp.enabled !== false);
    setVal("sp_heading", sp.heading);
    setVal("sp_note", sp.note);
    renderSponsorItems(Array.isArray(sp.items) ? sp.items : []);

    var c = m.contact || {};
    setVal("c_text", c.text);
    setVal("c_tg_id", c.telegramId);
    setVal("c_tg_url", c.telegramUrl);
    setVal("c_inapp_tg", m.inAppTelegramUrl || (m.inAppTelegram && m.inAppTelegram.url) || "");
    setVal("c_btn_copy", c.btnCopy || "کپی آیدی");
    setVal("c_btn_send", c.btnSend || "ارسال پیام");

    // §5 — release/download metadata
    setVal("latest_apk_version", m.latestApkVersion || "3.8");
    var lu = $("last-updated");
    if (lu) {
      if (m.lastUpdatedAt) {
        lu.textContent = "آخرین بروزرسانی: " + new Date(m.lastUpdatedAt).toLocaleString();
      } else {
        lu.textContent = "";
      }
    }

    // keep the purple header Telegram quick-link in sync with the live link
    var tg = $("tg_icon");
    if (tg && c.telegramUrl) tg.setAttribute("href", c.telegramUrl);

    toggleUrlWrap("ab");
    toggleUrlWrap("wb");
    refreshAllThumbs();
    updatePreview();
    updateJsonOut();
  }

  function toColorInput(hex) {
    var h = (hex || "").trim();
    return /^#[0-9a-fA-F]{6}$/.test(h) ? h : "#0b0b0f";
  }

  function toggleUrlWrap(prefix) {
    var wrap = $(prefix + "_url_wrap");
    var sel = $(prefix + "_action");
    if (wrap && sel) wrap.classList.toggle("hidden", sel.value !== "url");
  }

  // ---------------------------------------------------------------- thumbs
  function setThumb(imgId, phId, url) {
    var img = $(imgId), ph = $(phId);
    if (!img || !ph) return;
    if (url) {
      img.src = url;
      img.classList.remove("hidden");
      ph.classList.add("hidden");
    } else {
      img.removeAttribute("src");
      img.classList.add("hidden");
      ph.classList.remove("hidden");
    }
  }

  function refreshAllThumbs() {
    setThumb("ab_thumb", "ab_ph", trimv("ab_image"));
    setThumb("wb_thumb", "wb_ph", trimv("wb_image"));
    setThumb("al_thumb", "al_ph", trimv("app_logo"));
    setThumb("wl_thumb", "wl_ph", trimv("web_logo"));
  }

  // ---------------------------------------------------- sponsor config items
  // Detect the protocol of a raw share link (vless/vmess only). Anything else
  // is rejected so the app never receives an unsupported sponsor config.
  function detectProto(link) {
    var s = (link || "").trim().toLowerCase();
    if (s.indexOf("vless://") === 0) return "vless";
    if (s.indexOf("vmess://") === 0) return "vmess";
    return "";
  }

  // Read all sponsor item rows back into a clean array, dropping invalid links.
  function readSponsorItems() {
    var out = [];
    var rows = document.querySelectorAll("#sp_items .sp-item");
    rows.forEach(function (row, i) {
      var link = (row.querySelector(".sp-link").value || "").trim();
      var proto = detectProto(link);
      if (!link || !proto) return;   // skip empty / unsupported safely
      out.push({
        id: row.getAttribute("data-id") || ("sp_" + Date.now() + "_" + i),
        title: (row.querySelector(".sp-title").value || "").trim() || ("Sponsor " + (i + 1)),
        text: (row.querySelector(".sp-text").value || "").trim(),
        imageUrl: (row.querySelector(".sp-image").value || "").trim(),
        link: link,
        protocol: proto
      });
    });
    return out;
  }

  function sponsorItemTemplate(item, i) {
    var id = item.id || ("sp_" + Date.now() + "_" + i);
    var proto = item.protocol || detectProto(item.link) || "?";
    var div = document.createElement("div");
    div.className = "sp-item";
    div.setAttribute("data-id", id);
    div.innerHTML =
      '<div class="sp-head">' +
        '<span class="idx">کانفیگ ' + (i + 1) + '</span>' +
        '<span class="proto">' + proto.toUpperCase() + '</span>' +
        '<span class="spacer"></span>' +
        '<button class="btn btn-danger btn-sm sp-del">حذف</button>' +
      '</div>' +
      '<label>عنوان نمایشی</label>' +
      '<input class="sp-title" type="text" value="">' +
      '<label>متن تبلیغ (اختیاری)</label>' +
      '<input class="sp-text" type="text" value="">' +
      '<label>لینک کانفیگ (vless:// یا vmess://) — فقط داخلی، به کاربر نشان داده نمی‌شود</label>' +
      '<input class="sp-link" type="text" placeholder="vless://… یا vmess://…" value="">' +
      '<label>تصویر / گیف (اختیاری)</label>' +
      '<div class="media">' +
        '<div class="thumb"><img class="sp-thumb hidden"><span class="ph sp-ph">بدون مدیا</span></div>' +
        '<div class="ctl">' +
          '<input class="sp-image" type="text" placeholder="آدرس تصویر/گیف یا آپلود">' +
          '<div class="btns">' +
            '<button class="btn btn-ghost btn-sm sp-upload">⬆ آپلود مدیا</button>' +
            '<button class="btn btn-danger btn-sm sp-img-remove">حذف مدیا</button>' +
          '</div>' +
        '</div>' +
        '<input class="sp-file" type="file" accept="image/png,image/jpeg,image/webp,image/gif" class="hidden" style="display:none">' +
      '</div>';
    // fill values safely (avoids HTML injection via innerHTML)
    div.querySelector(".sp-title").value = item.title || "";
    div.querySelector(".sp-text").value = item.text || "";
    div.querySelector(".sp-link").value = item.link || "";
    div.querySelector(".sp-image").value = item.imageUrl || "";
    wireSponsorItem(div);
    return div;
  }

  function wireSponsorItem(row) {
    var linkEl = row.querySelector(".sp-link");
    var protoEl = row.querySelector(".proto");
    var imgEl = row.querySelector(".sp-image");
    var thumb = row.querySelector(".sp-thumb");
    var ph = row.querySelector(".sp-ph");

    function refreshThumb() {
      var url = (imgEl.value || "").trim();
      if (url) { thumb.src = url; thumb.classList.remove("hidden"); ph.classList.add("hidden"); }
      else { thumb.removeAttribute("src"); thumb.classList.add("hidden"); ph.classList.remove("hidden"); }
    }
    function refreshProto() {
      var p = detectProto(linkEl.value);
      protoEl.textContent = (p || "?").toUpperCase();
      protoEl.style.color = p ? "var(--violet2)" : "var(--red)";
    }

    linkEl.addEventListener("input", function () { refreshProto(); updateJsonOut(); });
    [".sp-title", ".sp-text"].forEach(function (sel) {
      row.querySelector(sel).addEventListener("input", updateJsonOut);
    });
    imgEl.addEventListener("input", function () { refreshThumb(); updateJsonOut(); });

    row.querySelector(".sp-del").addEventListener("click", function (e) {
      e.preventDefault();
      row.parentNode.removeChild(row);
      renumberSponsorItems();
      updateJsonOut();
    });
    row.querySelector(".sp-img-remove").addEventListener("click", function (e) {
      e.preventDefault(); imgEl.value = ""; refreshThumb(); updateJsonOut();
    });

    var fileEl = row.querySelector(".sp-file");
    row.querySelector(".sp-upload").addEventListener("click", function (e) {
      e.preventDefault(); fileEl.click();
    });
    fileEl.addEventListener("change", async function () {
      var f = fileEl.files && fileEl.files[0];
      if (!f) return;
      try {
        var url = await uploadMedia(f, "sponsor");
        imgEl.value = url; refreshThumb();
        setStatus("✓ مدیا آپلود شد — اکنون «انتشار» را بزنید تا زنده شود", "ok");
        updateJsonOut();
      } catch (err) {
        setStatus("خطا در آپلود: " + err.message, "bad");
      } finally { fileEl.value = ""; }
    });

    refreshProto();
    refreshThumb();
  }

  function renumberSponsorItems() {
    var rows = document.querySelectorAll("#sp_items .sp-item");
    rows.forEach(function (row, i) {
      var idx = row.querySelector(".idx");
      if (idx) idx.textContent = "کانفیگ " + (i + 1);
    });
    var box = $("sp_items");
    if (box && rows.length === 0) {
      box.innerHTML = '<div class="sp-empty">هنوز کانفیگ اسپانسری اضافه نشده است.</div>';
    }
  }

  function renderSponsorItems(items) {
    var box = $("sp_items");
    if (!box) return;
    box.innerHTML = "";
    (items || []).forEach(function (it, i) { box.appendChild(sponsorItemTemplate(it, i)); });
    renumberSponsorItems();
  }

  function addSponsorItem() {
    var box = $("sp_items");
    if (!box) return;
    var empty = box.querySelector(".sp-empty");
    if (empty) box.removeChild(empty);
    var i = box.querySelectorAll(".sp-item").length;
    box.appendChild(sponsorItemTemplate({ id: "sp_" + Date.now() + "_" + i }, i));
    renumberSponsorItems();
    updateJsonOut();
  }

  // ---------------------------------------------------------------- preview
  function updatePreview() {
    // ---- App preview (phone mockup) ----
    var abEnabled = checked("ab_enabled");
    var abMedia = checked("ab_media_visible");
    var bg = trimv("ab_bg_hex") || "#0B0B0F";
    var fg = trimv("ab_text_hex") || "#F4F2FB";
    var banner = $("pv_banner");
    if (banner) {
      banner.style.background = bg;
      banner.style.color = fg;
      banner.style.borderColor = "rgba(255,255,255,.12)";
      banner.style.opacity = abEnabled ? "1" : "0.32";
    }
    var pvTitle = $("pv_title"), pvSub = $("pv_subtitle"), pvImg = $("pv_img");
    if (pvTitle) { pvTitle.style.color = fg; pvTitle.textContent = trimv("ab_title") || "محل تبلیغ شما"; }
    if (pvSub) { pvSub.style.color = fg; pvSub.textContent = trimv("ab_subtitle"); }
    var abImg = trimv("ab_image");
    if (pvImg) {
      if (abImg && abMedia) {
        pvImg.src = abImg; pvImg.classList.remove("hidden");
        if (pvTitle) pvTitle.classList.add("hidden");
        if (pvSub) pvSub.classList.add("hidden");
      } else {
        pvImg.classList.add("hidden");
        if (pvTitle) pvTitle.classList.remove("hidden");
        if (pvSub) pvSub.classList.remove("hidden");
      }
    }
    var appLogoUrl = trimv("app_logo");
    var pvLogo = $("pv_logo");
    if (pvLogo) {
      if (appLogoUrl) { pvLogo.src = appLogoUrl; pvLogo.style.display = "block"; }
      else pvLogo.style.display = "none";
    }

    // ---- Website preview ----
    var wbEnabled = checked("wb_enabled");
    var wbMedia = checked("wb_media_visible");
    var wpvBanner = $("wpv_banner");
    if (wpvBanner) wpvBanner.style.opacity = wbEnabled ? "1" : "0.32";
    var wpvTitle = $("wpv_title"), wpvSub = $("wpv_subtitle"), wpvImg = $("wpv_img");
    if (wpvTitle) wpvTitle.textContent = trimv("wb_title") || "محل تبلیغ شما";
    if (wpvSub) wpvSub.textContent = trimv("wb_subtitle");
    var wbImg = trimv("wb_image");
    if (wpvImg) {
      if (wbImg && wbMedia) {
        wpvImg.src = wbImg; wpvImg.classList.remove("hidden");
        if (wpvTitle) wpvTitle.classList.add("hidden");
        if (wpvSub) wpvSub.classList.add("hidden");
      } else {
        wpvImg.classList.add("hidden");
        if (wpvTitle) wpvTitle.classList.remove("hidden");
        if (wpvSub) wpvSub.classList.remove("hidden");
      }
    }
    var webLogoUrl = trimv("web_logo");
    var wpvLogo = $("wpv_logo");
    if (wpvLogo) {
      if (webLogoUrl) { wpvLogo.src = webLogoUrl; wpvLogo.style.display = "block"; }
      else wpvLogo.style.display = "none";
    }

    refreshAllThumbs();
  }

  function updateJsonOut() {
    var out = $("json_out");
    if (out) out.value = JSON.stringify(readForm(), null, 2);
  }

  // ---------------------------------------------------------------- github
  function ghHeaders(token) {
    return {
      Authorization: "token " + token,
      Accept: "application/vnd.github+json"
    };
  }

  function b64encodeUtf8(str) {
    return btoa(unescape(encodeURIComponent(str)));
  }
  function b64decodeUtf8(b64) {
    return decodeURIComponent(escape(atob((b64 || "").replace(/\n/g, ""))));
  }

  async function ghGetFile(repo, path, branch, token) {
    var url = "https://api.github.com/repos/" + repo + "/contents/" +
      encodeURI(path) + "?ref=" + encodeURIComponent(branch);
    var res = await fetch(url, { headers: ghHeaders(token) });
    if (res.status === 404) return { sha: null, content: null };
    if (!res.ok) throw new Error("GET " + res.status);
    var j = await res.json();
    return { sha: j.sha, content: b64decodeUtf8(j.content) };
  }

  async function ghPutFile(repo, path, branch, token, base64Content, sha, message) {
    var url = "https://api.github.com/repos/" + repo + "/contents/" + encodeURI(path);
    var body = {
      message: message || "chore: update from admin console",
      content: base64Content,
      branch: branch
    };
    if (sha) body.sha = sha;
    var res = await fetch(url, {
      method: "PUT",
      headers: ghHeaders(token),
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      var t = await res.text();
      throw new Error("PUT " + res.status + " " + t);
    }
    return res.json();
  }

  // Read a File into base64 (raw binary, no data: prefix).
  function fileToBase64(file) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () {
        var s = r.result || "";
        var comma = s.indexOf(",");
        resolve(comma >= 0 ? s.slice(comma + 1) : s);
      };
      r.onerror = reject;
      r.readAsDataURL(file);
    });
  }

  function extFor(file) {
    var t = (file.type || "").toLowerCase();
    if (t.indexOf("gif") >= 0) return "gif";
    if (t.indexOf("png") >= 0) return "png";
    if (t.indexOf("webp") >= 0) return "webp";
    if (t.indexOf("jpeg") >= 0 || t.indexOf("jpg") >= 0) return "jpg";
    var n = (file.name || "").toLowerCase();
    var m = n.match(/\.(gif|png|webp|jpe?g)$/);
    return m ? m[1].replace("jpeg", "jpg") : "png";
  }

  // Upload a media file as a binary blob to adminpanel/media/, return raw URL.
  async function uploadMedia(file, kind) {
    var token = trimv("gh_token") || localStorage.getItem(LS_TOKEN) || "";
    var repo = trimv("gh_repo") || "prfgame/prf-VPN";
    var branch = trimv("gh_branch") || "main";
    if (!token) throw new Error("ابتدا توکن گیت‌هاب را در تب «انتشار» وارد کنید");
    if (file.size > 9 * 1024 * 1024) throw new Error("حجم فایل باید کمتر از ۹ مگابایت باشد");

    var ts = Date.now();
    var ext = extFor(file);
    var path = "adminpanel/media/" + kind + "_" + ts + "." + ext;
    var b64 = await fileToBase64(file);

    setStatus("در حال آپلود مدیا…");
    var existing = { sha: null };
    try { existing = await ghGetFile(repo, path, branch, token); } catch (e) { existing = { sha: null }; }
    await ghPutFile(repo, path, branch, token, b64, existing.sha, "chore: upload media " + path);

    // Raw URL with cache-buster so previews/live consumers refresh immediately.
    var raw = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
    return raw + "?v=" + ts;
  }

  // §4.2/§5 — validate the In-App Telegram Link. Must be empty (allowed: falls
  // back to contact link) or a real https://t.me/ or https://telegram.me/ URL.
  function isValidTelegramUrl(u) {
    if (!u) return true; // empty is allowed (app falls back to contact link)
    return /^https:\/\/(t\.me|telegram\.me)\/[A-Za-z0-9_+/?=#%.\-]+$/i.test(u.trim());
  }

  // ---------------------------------------------------------------- publish
  async function applyAndPublish() {
    var token = trimv("gh_token");
    var repo = trimv("gh_repo") || "prfgame/prf-VPN";
    var branch = trimv("gh_branch") || "main";
    var path = trimv("gh_path") || "adminpanel/app_config.json";
    if (!token) { setStatus("توکن گیت‌هاب را وارد کنید (تب انتشار)", "bad"); return; }

    // §4.2 — reject an invalid Telegram link BEFORE we touch the network so a
    // bad value can never be published.
    var tgUrl = trimv("c_inapp_tg");
    if (!isValidTelegramUrl(tgUrl)) {
      setStatus("لینک تلگرام نامعتبر است — باید با https://t.me/ یا https://telegram.me/ شروع شود", "bad");
      var tgField = $("c_inapp_tg");
      if (tgField) { tgField.focus(); tgField.classList.add("invalid"); }
      return;
    }
    var tgFieldOk = $("c_inapp_tg");
    if (tgFieldOk) tgFieldOk.classList.remove("invalid");

    localStorage.setItem(LS_TOKEN, token);

    var model = readForm();
    // §5 — atomic save: build the full JSON once, then a single PUT writes it.
    var json = JSON.stringify(model, null, 2);
    var out = $("json_out");
    if (out) out.value = json;

    setStatus("در حال انتشار…");
    try {
      var existing = await ghGetFile(repo, path, branch, token);
      await ghPutFile(repo, path, branch, token, b64encodeUtf8(json), existing.sha,
        "chore: publish app_config v" + model.version);
      window.__ver = model.version;
      var verBadge = $("ver-badge");
      if (verBadge) verBadge.textContent = "v" + model.version;
      // §5 — "Saved ✓" + a human lastUpdatedAt stamp.
      var stamp = new Date(model.lastUpdatedAt || Date.now());
      setStatus("Saved ✓ — منتشر شد (نسخه " + model.version + " · " + stamp.toLocaleString() + ")", "ok");
      var lu = $("last-updated");
      if (lu) lu.textContent = "آخرین بروزرسانی: " + stamp.toLocaleString();
    } catch (e) {
      setStatus("خطا در انتشار: " + e.message, "bad");
    }
  }

  async function reloadFromGithub() {
    var token = localStorage.getItem(LS_TOKEN) || trimv("gh_token");
    var repo = trimv("gh_repo") || "prfgame/prf-VPN";
    var branch = trimv("gh_branch") || "main";
    var path = trimv("gh_path") || "adminpanel/app_config.json";
    setStatus("در حال بارگذاری…");
    try {
      var txt = null;
      try {
        var r = await fetch(DEFAULT_CONFIG_URL + "?t=" + Date.now(), { cache: "no-store" });
        if (r.ok) txt = await r.text();
      } catch (e) {}
      if (!txt && token) {
        var f = await ghGetFile(repo, path, branch, token);
        txt = f.content;
      }
      if (txt) {
        var parsed = JSON.parse(txt);
        var m = migrate(parsed);
        writeForm(m);
        setStatus("✓ بارگذاری شد", "ok");
      } else {
        writeForm(defaultModel());
        setStatus("فایلی یافت نشد — مقادیر پیش‌فرض بارگذاری شد", "");
      }
    } catch (e) {
      writeForm(defaultModel());
      setStatus("بارگذاری ناموفق: " + e.message, "bad");
    }
  }

  // Merge a loaded config onto defaults, upgrading legacy `ad` → appBanner.
  function migrate(parsed) {
    var d = defaultModel();
    var m = Object.assign({}, d, parsed || {});
    m.version = parsed && parsed.version ? parsed.version : 0;

    // appBanner: prefer explicit appBanner, fall back to legacy ad block.
    if (parsed && parsed.appBanner) {
      m.appBanner = Object.assign({}, d.appBanner, parsed.appBanner);
    } else if (parsed && parsed.ad) {
      m.appBanner = Object.assign({}, d.appBanner, {
        enabled: parsed.ad.enabled !== false,
        title: parsed.ad.title,
        subtitle: parsed.ad.subtitle,
        bgColor: parsed.ad.bgColor,
        textColor: parsed.ad.textColor,
        imageUrl: parsed.ad.imageUrl,
        mediaVisible: !!parsed.ad.imageUrl,
        action: parsed.ad.action,
        actionUrl: parsed.ad.actionUrl
      });
    } else {
      m.appBanner = Object.assign({}, d.appBanner);
    }

    m.websiteBanner = Object.assign({}, d.websiteBanner, (parsed && parsed.websiteBanner) || {});
    m.appLogo = Object.assign({}, d.appLogo, (parsed && parsed.appLogo) || {});
    m.websiteLogo = Object.assign({}, d.websiteLogo, (parsed && parsed.websiteLogo) || {});
    // Sponsor: new shape is { enabled, heading, note, items[] }. Upgrade the
    // legacy single-card shape ({title,text,imageUrl,url}) into one item if it
    // carried a real config link; otherwise start with an empty list.
    var ps = (parsed && parsed.sponsor) || {};
    var items = [];
    if (Array.isArray(ps.items)) {
      items = ps.items.filter(function (it) {
        return it && (detectProto(it.link) !== "");
      }).map(function (it, i) {
        return {
          id: it.id || ("sp_" + i),
          title: it.title || ("Sponsor " + (i + 1)),
          text: it.text || "",
          imageUrl: it.imageUrl || "",
          link: (it.link || "").trim(),
          protocol: detectProto(it.link)
        };
      });
    } else if (ps.url && detectProto(ps.url)) {
      items = [{
        id: "sp_0", title: ps.title || "Sponsor 1", text: ps.text || "",
        imageUrl: ps.imageUrl || "", link: ps.url.trim(), protocol: detectProto(ps.url)
      }];
    }
    m.sponsor = {
      enabled: ps.enabled !== false,
      heading: ps.heading || "",
      note: ps.note || "",
      items: items
    };
    m.contact = Object.assign({}, d.contact, (parsed && parsed.contact) || {});
    // In-app Telegram link: accept flat field or nested object.
    m.inAppTelegramUrl =
      (parsed && parsed.inAppTelegramUrl) ||
      (parsed && parsed.inAppTelegram && parsed.inAppTelegram.url) ||
      "";
    return m;
  }

  // ---------------------------------------------------------------- auth
  async function tryLogin() {
    var u = val("u");
    var p = val("p");
    var uh = await sha256(u);
    var ph = await sha256(p);
    if (uh === USER_HASH && ph === PASS_HASH) {
      sessionStorage.setItem(LS_AUTH, "1");
      showApp();
    } else {
      $("loginErr").textContent = "نام کاربری یا رمز نادرست است";
    }
  }

  function showApp() {
    $("login").classList.add("hidden");
    $("app").classList.remove("hidden");
    var savedToken = localStorage.getItem(LS_TOKEN);
    if (savedToken && $("gh_token")) $("gh_token").value = savedToken;
    reloadFromGithub();
    // load the privacy-first Users stats (default tab) and keep them fresh
    startUsersAutoRefresh();
  }

  function logout() {
    sessionStorage.removeItem(LS_AUTH);
    location.reload();
  }

  // ---------------------------------------------------------------- users
  // PRIVACY-FIRST aggregate stats. The app reports only opaque, non-reversible
  // anonymous counts to a public aggregate-counter service (no PII, no IP, no
  // device id, no location). Here we READ those integers and show:
  //   • All     = lifetime distinct installs (counted once per install)
  //   • Online  = distinct heartbeats in the current/previous 5-min window
  //   • Offline = All − Online (never negative)
  // Nothing per-user is ever fetched or shown — only aggregate totals.
  var STATS_NS = "professorvpn";
  var ONLINE_WINDOW_MS = 5 * 60 * 1000;
  var usersTimer = null;

  // Read a counter's current value WITHOUT incrementing it.
  //
  // IMPORTANT (CORS): counterapi.dev v1 only serves a clean, CORS-open response on
  // the TRAILING-SLASH url ("…/key/"). The bare "…/key" returns 301 Moved
  // Permanently, and the browser's follow-up to the redirect target is blocked by
  // CORS (that was the console-error bug). The trailing slash returns the current
  // {count} directly WITHOUT incrementing and WITH `access-control-allow-origin:*`.
  // The v2 / abacus shapes 404 for our namespace, so we drop them.
  async function readCounter(key) {
    var safe = key.replace(/[^A-Za-z0-9_]/g, "");
    var urls = [
      "https://api.counterapi.dev/v1/" + STATS_NS + "/" + safe + "/"  // trailing slash = CORS-clean read
    ];
    for (var i = 0; i < urls.length; i++) {
      try {
        var r = await fetch(urls[i] + "?t=" + Date.now(), { cache: "no-store" });
        if (!r.ok) continue;
        var j = await r.json();
        var v = (j && (j.count != null ? j.count : (j.value != null ? j.value : null)));
        if (v != null && !isNaN(v)) return Number(v);
      } catch (e) { /* unreachable / blocked — fall through to 0 */ }
    }
    return 0;
  }

  function currentWindow() { return Math.floor(Date.now() / ONLINE_WINDOW_MS); }

  async function loadUsers() {
    var onlineEl = $("u_online"), offlineEl = $("u_offline"), allEl = $("u_all");
    var upd = $("u_updated");
    if (upd) upd.textContent = "در حال بارگذاری…";

    // Keys are BARE (no namespace prefix) because the namespace is already
    // STATS_NS ("professorvpn") — this matches exactly what the app reports, so
    // /v1/professorvpn/installs_total/ and /v1/professorvpn/online_<w>/ resolve.
    // Online = sum of the current and previous 5-minute window buckets (covers a
    // user whose last heartbeat landed just before the boundary).
    var w = currentWindow();
    var results = await Promise.all([
      readCounter("installs_total"),
      readCounter("online_" + w),
      readCounter("online_" + (w - 1))
    ]);
    var all = results[0] || 0;
    var online = (results[1] || 0) + (results[2] || 0);
    if (online > all) online = all;            // clamp — online can't exceed all
    var offline = Math.max(0, all - online);

    if (onlineEl) onlineEl.textContent = online.toLocaleString("fa-IR");
    if (offlineEl) offlineEl.textContent = offline.toLocaleString("fa-IR");
    if (allEl) allEl.textContent = all.toLocaleString("fa-IR");
    if (upd) {
      var t = new Date();
      upd.textContent = "به‌روز شد " +
        ("0" + t.getHours()).slice(-2) + ":" + ("0" + t.getMinutes()).slice(-2);
    }
  }

  function startUsersAutoRefresh() {
    if (usersTimer) return;
    loadUsers();
    usersTimer = setInterval(loadUsers, 60 * 1000); // refresh once a minute
  }

  // ---------------------------------------------------------------- wire
  var TABS = ["users", "appb", "webb", "logos", "sponsor", "contact", "publish"];

  function bindMediaUploader(fileId, btnId, removeId, textId, kind) {
    var fileEl = $(fileId), btn = $(btnId), rm = $(removeId), txt = $(textId);
    if (btn && fileEl) {
      btn.addEventListener("click", function (e) { e.preventDefault(); fileEl.click(); });
      fileEl.addEventListener("change", async function () {
        var f = fileEl.files && fileEl.files[0];
        if (!f) return;
        try {
          var url = await uploadMedia(f, kind);
          if (txt) txt.value = url;
          setStatus("✓ مدیا آپلود شد — اکنون «انتشار» را بزنید تا زنده شود", "ok");
          updatePreview(); updateJsonOut();
        } catch (err) {
          setStatus("خطا در آپلود: " + err.message, "bad");
        } finally {
          fileEl.value = "";
        }
      });
    }
    if (rm && txt) {
      rm.addEventListener("click", function (e) {
        e.preventDefault();
        txt.value = "";
        updatePreview(); updateJsonOut();
      });
    }
  }

  function wire() {
    $("loginBtn").addEventListener("click", tryLogin);
    $("p").addEventListener("keydown", function (e) { if (e.key === "Enter") tryLogin(); });
    $("logoutBtn").addEventListener("click", logout);
    $("applyBtn").addEventListener("click", applyAndPublish);
    $("reloadBtn").addEventListener("click", reloadFromGithub);

    // tab switching
    document.querySelectorAll(".tab").forEach(function (t) {
      t.addEventListener("click", function () {
        document.querySelectorAll(".tab").forEach(function (x) { x.classList.remove("active"); });
        t.classList.add("active");
        var name = t.getAttribute("data-tab");
        TABS.forEach(function (n) {
          var el = $("tab-" + n);
          if (el) el.classList.toggle("hidden", n !== name);
        });
      });
    });

    // live preview/json bindings — text & toggles
    var liveIds = [
      "ab_enabled", "ab_title", "ab_subtitle", "ab_image", "ab_media_visible", "ab_action_url",
      "wb_enabled", "wb_title", "wb_subtitle", "wb_image", "wb_media_visible", "wb_action_url",
      "app_logo", "web_logo",
      "sp_enabled", "sp_heading", "sp_note",
      "c_text", "c_tg_id", "c_tg_url", "c_inapp_tg", "c_btn_copy", "c_btn_send"
    ];
    liveIds.forEach(function (id) {
      var el = $(id);
      if (!el) return;
      el.addEventListener("input", function () { updatePreview(); updateJsonOut(); });
      el.addEventListener("change", function () { updatePreview(); updateJsonOut(); });
    });

    // live-sync the purple header Telegram link as the contact url is typed
    var tgUrl = $("c_tg_url");
    if (tgUrl) tgUrl.addEventListener("input", function () {
      var tg = $("tg_icon");
      if (tg && tgUrl.value.trim()) tg.setAttribute("href", tgUrl.value.trim());
    });

    // §4.2 — live validation of the In-App Telegram link; clear the red border
    // as soon as the value becomes valid (or empty).
    var inappTg = $("c_inapp_tg");
    if (inappTg) inappTg.addEventListener("input", function () {
      if (isValidTelegramUrl(inappTg.value)) inappTg.classList.remove("invalid");
      else inappTg.classList.add("invalid");
    });

    // action selects → toggle url field
    var ab = $("ab_action");
    if (ab) ab.addEventListener("change", function () { toggleUrlWrap("ab"); updatePreview(); updateJsonOut(); });
    var wb = $("wb_action");
    if (wb) wb.addEventListener("change", function () { toggleUrlWrap("wb"); updatePreview(); updateJsonOut(); });

    // color sync — app banner
    bindColorPair("ab_bg_color", "ab_bg_hex");
    bindColorPair("ab_text_color", "ab_text_hex");

    // media uploaders
    bindMediaUploader("ab_file", "ab_upload_btn", "ab_remove_btn", "ab_image", "appbanner");
    bindMediaUploader("wb_file", "wb_upload_btn", "wb_remove_btn", "wb_image", "webbanner");
    bindMediaUploader("al_file", "al_upload_btn", "al_remove_btn", "app_logo", "applogo");
    bindMediaUploader("wl_file", "wl_upload_btn", "wl_remove_btn", "web_logo", "weblogo");

    // sponsor: dynamic multi-item editor (each item wires itself; add button here)
    var spAdd = $("sp_add_btn");
    if (spAdd) spAdd.addEventListener("click", function (e) { e.preventDefault(); addSponsorItem(); });

    // users: manual refresh of the privacy-first aggregate stats
    var uRefresh = $("u_refresh");
    if (uRefresh) uRefresh.addEventListener("click", function (e) { e.preventDefault(); loadUsers(); });

    if (sessionStorage.getItem(LS_AUTH) === "1") showApp();
  }

  function bindColorPair(colorId, hexId) {
    var c = $(colorId), h = $(hexId);
    if (!c || !h) return;
    c.addEventListener("input", function () {
      h.value = c.value.toUpperCase();
      updatePreview(); updateJsonOut();
    });
    h.addEventListener("input", function () {
      c.value = toColorInput(h.value);
      updatePreview(); updateJsonOut();
    });
  }

  document.addEventListener("DOMContentLoaded", wire);
})();
