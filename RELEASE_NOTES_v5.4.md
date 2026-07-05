# PROFESSOR·VPN — v5.4 (versionCode 35)

## خلاصه فارسی (Persian summary)
این نسخه دقیقاً همان چیزهایی که گفتی رو درست می‌کنه:

- **اتصال مستقیم به کانفیگ — بدون DNS و بدون پروکسی واسط.** کل بلوک `dns`،
  فرگمنت `fragment` و زنجیرهٔ `dialerProxy` که در ۵.۳ بود حذف شد. حالا فقط:
  `TUN → tun2socks → SOCKS داخلی → خروجی proxy → مستقیم به سرور کانفیگ`.
  هیچ چیز بین دستگاه و سرور کانفیگ نیست.
- **پینگ دیگه دروغ نیست.** پینگ هر کانفیگ حالا از *همان* خروجی‌ای که
  اتصال واقعی استفاده می‌کند اندازه‌گیری می‌شود (مسیر یکسان). چون در ۵.۳ پینگ
  از مسیر کوتاه‌تری (بدون `dialerProxy`) اندازه گرفته می‌شد، عدد ۱۰۰ نشان می‌داد
  ولی اتصال واقعی ۷۰۰ بود. حالا مسیر پینگ و اتصال یکی است.
- **نوار تست کانکشن نرم حرکت می‌کند.** قبلاً روی ۲ گیر می‌کرد و یهو ۱۰۰ می‌شد.
  حالا یک تایمر پیوسته نوار را به‌آرامی جلو می‌برد و انیمیشن هم بین اعداد
  حرکت روان دارد.
- **اتصال واقعی، نه سبزِ الکی.** قبل از نشان‌دادن «متصل»، برنامه دو گیت سخت
  را چک می‌کند: (۱) کتابخانهٔ بومی tun2socks لود شده باشد؛ (۲) بایت واقعی از
  خروجی کانفیگ عبور کند. اگر سروری بایت واقعی جابه‌جا نکند رد می‌شود.

---

## What changed (English)

### 1) DIRECT connection — no DNS block, no proxy intermediary
`XrayConfigBuilder` was reduced to the smallest config a real Xray core needs
to carry traffic and dial the selected config's server **directly**:

- **Removed the `dns` block entirely** (no custom resolvers / queryStrategy).
- **Removed the `fragment` freedom dialer** and the **`dialerProxy` sockopt**
  chain that v5.3 added.
- Routing is dead simple: stats-api → api, `geoip:private` → direct,
  everything else (tcp+udp) → the proxy outbound.
- `sockopt` keeps only `tcpNoDelay` + `tcpKeepAliveIdle` (no mark, no
  fragment, no dialerProxy).

Live path: `TUN → tun2socks → local SOCKS inbound → proxy outbound → server`.

### 2) Honest ping (fixes "pings 100ms, connects at 700ms")
The per-config ping (`buildPingConfig`) now uses the **exact same outbound**
the live connect uses. In v5.3 the ping timed a shorter path (it didn't walk
the `dialerProxy` chain that the live tunnel used), so the numbers diverged.
Same dialing path now ⇒ a green ping is the real latency the tunnel will have.

### 3) Smooth Auto-Test progress bar (fixes "stuck at 2 then jumps to 100")
`ConnectivityProbe` now drives the bar from a steady 120 ms time-based ticker
that eases toward 95% over the probe budget, so it **always moves**. Real work
still "pulls" the bar forward when it's ahead of the clock. `AutoTestActivity`
additionally tweens the `ProgressBar` between values with a `DecelerateInterpolator`
for buttery motion. The final 100% is emitted only when the probe completes.

### 4) Real-tunnel proof retained
Before reporting **Connected**, two hard gates still apply:
- GATE 1 — native tun2socks (`hev-socks5-tunnel`) must be loaded.
- GATE 2 — real bytes must flow through the proxy outbound (HTTP response
  bytes OR proxy-outbound counter movement). A server that accepts the SOCKS
  CONNECT then drops every byte is rejected instead of showing a fake green.

## Build
- Signed universal APK, all 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64).
- `versionCode=35`, `versionName="5.4"`, signed v2.
- Output: `build/ProfessorVPN-v5.4-universal.apk`.

## Notes
- Only VLESS / VMESS are supported (project policy).
- No fixed proxy/DNS — works across Iranian ISPs and devices, using the
  selected config's full speed.
