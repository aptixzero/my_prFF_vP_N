# Professor VPN v11.4

## The search does the steps, and a ping moves the row

v11.3 advanced the counter when a worker picked a row up. The row itself
did not get a ping, so server 1 stayed where it was for hours.

### The formula

1. START SEARCH opens the connection-test page.
2. The page reads the network and sets the matching settings.
3. The best bank supplies 120 configs. They are added to the list.
4. A few are pinged at a time.
5. A config that returns a ping is pinned to the top.
6. A config that does not return a ping goes to the bottom.

### What changed

- The counter counts finished pings only. A pickup is not a ping.
- A positive core round trip is shown at once and the row moves to the top.
- A finished attempt with no ping goes to the bottom. It is not left on
  "Pinging".
- The list reload keeps that order. A dead row cannot jump back to the top.

versionCode 95 / versionName 11.4. Same signing key as v6.7–v11.3.
