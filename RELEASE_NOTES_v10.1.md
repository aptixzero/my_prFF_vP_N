# Professor VPN v10.1

## Close means off, use the whole link, announce to the right version

**Auto-search**
- Closing the app completely (removing it from recents) switches the auto-search off.
  Minimising or locking the screen keeps it running. It is never resumed after a
  crash, a reboot or an update, and the old boot receiver is gone.
- Closing the app while the search page is still analysing the network also cancels
  the search that would have started afterwards.
- The VPN connection itself is not touched when the app is closed.

**Speed and stability of the connection**
- The artificial limits are gone: no TCP window clamp (it capped speed at
  ~3-7 Mbit/s on a typical link), large buffers, 300 s idle window, 30 s TCP
  timeout, 120 s tunnel timeout. MPTCP is off in every mode.
- If the selected server fails the connect check, the app tries the next best
  working config by itself (up to three, never the same one twice).
- A server that dies while connected is replaced after about 3 minutes instead of
  about 10.

**Finding a working config**
- Dead configs are no longer probed twice when the first result is definitive.
- The first probe starts sooner (the TCP pre-check is twice as wide).
- A config is shown green only after it also downloads a real 100 KB through a fresh
  tunnel, so "it pings" now means "it connects".

**Announcements**
- New push notification and in-app announcement, each with its own text and
  audience: users on the newest version, users on older versions, or everyone.
- Delivered once per message, also when the app is closed.
- The app reads the control panel through two independent addresses.

versionCode 82. Same signing key as v10. Universal APK.
