# Professor VPN — Control Panel (v4.6)

Static admin console. Source of truth lives in the private repo
`aptixzero/prf-vpn-admin`. This branch is the served copy for GitHub Pages.

Login is SHA-256 gated; the GitHub token is entered at runtime and stored only
in the browser. Publishing writes `adminpanel/app_config.json` in the public
`aptixzero/PRF_VPN` repo, which the app fetches on launch.
