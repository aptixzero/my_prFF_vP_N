# Professor VPN v10.4

## New config banks, a real ping service, and a sweep that walks in order

- **The config sources are replaced.** The v10.3 and earlier feed list is no
  longer fetched (it stays in the repository for the record). The app now walks
  **100 curated banks** — 50 VLESS + 50 VMESS raw files, each last committed
  within a month and >90% pure — and the app's own legacy state for the old
  feeds is cleared once, so nothing lingers and nothing is lost.
- **A batch is 120 configs: 60 VLESS + 60 VMESS**, taken from the CURRENT bank
  of each kind. The next batch continues the SAME bank without repeating a
  single config, and when a bank runs out the walker switches to the next one —
  exactly the walk you described.
- **Every search picks the bank by measuring your connection.** The
  connection-test page races a rotating window of banks with real TCP samples,
  keeps the one that answers best here, and starts its sweep there — two
  searches in a row never open with the same bank.
- **A config can never be repeated.** One app-wide seen-set covers the banks,
  the corpus, the Free list and My Configs.
- **The ping service is now a real setting.** Eight live endpoints (Cloudflare
  ×2, Google ×2, Microsoft, Apple, Mozilla, Ubuntu) — each with a **CHECK**
  button that tests it on YOUR connection right now and reports OK + latency,
  or that it does not answer. AUTOMATIC follows the link type (cellular and
  Wi-Fi are filtered differently) and switches to the fastest service your own
  Check run measured. The sweep, the connect-path ping and the post-connect
  number all use the same service.
- **CDN / fetch mirror, automatic.** `raw.githubusercontent.com` is filtered on
  many Iranian links, so the banks can be fetched through jsDelivr or its Fastly
  edge instead; the app measures which mirror answers fastest here and uses it.
- **The sweep starts from the first config and walks down.** It used to re-rank
  the batch by socket speed, so it began somewhere in the middle of the list —
  your port policy is still honoured as a preference on top of arrival order.
- **The ping bar starts at 0% and moves one step per config.** v10.3 pre-loaded
  it with the rows the socket wave had already settled, which is why it jumped
  to ~50% the moment the batch landed.
- **Numbering stays `Server N`** — one monotonic sequence, never a feed's real
  name.

versionCode 85 / versionName 10.4. Same signing key as v10.3, so it installs
over the existing app.
