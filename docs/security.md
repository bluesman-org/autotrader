# Sign-in, recovery and HTTPS

The app holds **one account**. The Thymeleaf pages sign in with a form and a session cookie; the
JSON API keeps **HTTP Basic** with the same account, so scripts and Postman need no session. A fresh
installation has no account: `/` and `/login` send the reader to `/signup`, where the first account
is created, signed in and taken to the live page. Once it exists, the signup page says so and
refuses; there is deliberately no second account — the app trades one Bitvavo account, so another
user would have nothing of their own.

## What is open

| Path | Who gets in |
| --- | --- |
| `/`, `/login`, `/signup`, `/forgot-password`, `/reset-password` | anyone |
| `/css/**`, `/js/**`, `/images/**`, `/favicon.ico`, `/error` | anyone |
| `/service-worker.js`, `/manifest.webmanifest`, `/icon.svg` | anyone — static Web Push assets, no account data |
| `/actuator/health` | anyone (the monitoring probe) |
| `/webhook/**` | anyone — the TradingView webhook authenticates itself with its own key |
| `/v3/api-docs/**` | a signed-in account, and the machine itself (the specification exporter fetches it over the loopback at startup) |
| `/ui/**`, `/api/**`, `/swagger-ui/**`, everything else | a signed-in account |

A page that needs a sign-in redirects to `/login`; an API request answers **401** with a
`WWW-Authenticate: Basic` header, so a script sees what happened instead of a login page. CSRF is on
for the browser forms (Thymeleaf's `th:action` inserts the token by itself) and off for `/api/**` and
`/webhook/**`, which authenticate per request. The session cookie states `SameSite=Strict` itself,
because `/api/**` accepts that cookie with CSRF off.

The exporter's loopback rule is only trustworthy because the app runs with
`server.forward-headers-strategy=native`: Tomcat rewrites the client address from `X-Forwarded-For`
only when the peer is a trusted proxy on this host, and it ignores the `Forwarded` header altogether.
Spring's own `framework` strategy rewrites the address from headers a remote caller can set, which
would let anyone claim the loopback and read the API shape.

## The account

- **Username** 3–40 characters, letters, digits, dots, underscores and hyphens, stored lowercased.
- **Email** stored lowercased; it is how a forgotten password is recovered.
- **Password** at least 10 characters, stored only as a BCrypt hash; the settings page changes it and
  asks for the current one first.
- The signup and recovery pages never echo a password back into the page.

## Password recovery

1. `/forgot-password` takes an address and **always** answers *"We'll send a recovery email to this
   address if it exists."* — an address that is not the account's is indistinguishable from the one
   that is. `POST /api/password-recovery` (Basic) answers the same sentence.
2. When the address matches, a mail goes out with
   `https://at.bluris.nl/autotrader/reset-password?token=…`. The link is built from
   `autotrader.security.public-url` (`AUTOTRADER_PUBLIC_URL`), so the address in the mail is the
   address the app is reached on.
3. The token is a 256-bit random value; the database stores only its **SHA-256 hash**, so a copy of
   the database cannot be turned into working links. It is **single-use** — the reset claims it with
   one conditional update, so two overlapping submissions cannot both set a password — and expires
   after `autotrader.security.reset-token-minutes` (30 by default). A second request within the
   2-minute cooldown keeps the link that is already on its way (and sends nothing); after that a
   fresh link replaces the old one.
   The mail leaves on a single background thread, so the page answers as fast for an address that is
   the account's as for one that is not — the answer time is not an oracle either.
4. `/reset-password?token=…` sets the new password and spends the token; a broken rule (too short,
   not repeated) comes back on the page, a spent or unknown token says the link is no longer valid.

The mail goes out through the SMTP server stored on the settings page ("Mail (password recovery)")
or through `PUT /api/mail-settings`. The password is stored encrypted (AES-256-GCM under
`ENCRYPTION_MASTER_KEY`) and is write-only; leaving it blank keeps the stored one, so the host can be
corrected without re-typing it. No SMTP environment variables are required.

## Web Push

Live alerts also go to subscribed browsers (see "Alerts" in `docs/live-engine.md`). Only three push
assets are public: `service-worker.js`, `manifest.webmanifest` and `icon.svg`. They are static and
carry no account data. The subscription API (`/api/push/**`) and the settings page need a signed-in
session; CSRF is off for `/api/**` like the rest of the API, and the session cookie is
`SameSite=Strict`.

- **VAPID key pair.** Generated once on the first request for the public key. The private key is
  encrypted with `ENCRYPTION_MASTER_KEY` (AES-256-GCM) in `push_settings`; the public key is not
  secret. Deleting `push_settings` rotates the pair and invalidates every browser subscription, so
  each device must enable notifications again. Keep it in the database backups.
- **Subscriptions.** Stored in `push_subscriptions`, keyed by the push service endpoint. That
  endpoint is a capability URL: anyone who holds it can send a notification to that browser. It is
  never rendered back into a page or returned by the API.
- **Transport.** The server encrypts each payload to the browser's p256dh key with its auth secret;
  the push service sees the endpoint, the timing and the message size, not the order details. The
  notification is only a short summary and opens `/ui/live`.
- **Gone devices.** A push service that answers 404 or 410 removes the subscription; other failures
  keep it and are logged. The trading path never waits for a push.
- **Browser requirements.** HTTPS (localhost is the development exception), and on iOS the site must
  be added to the home screen before Web Push is available (iOS 16.4 and later). Android Chrome works
  in the browser.

## Served over HTTPS

Caddy on the host terminates TLS for `at.bluris.nl` and proxies to the app; Spring runs behind it
with `server.forward-headers-strategy=native`, Tomcat's trust list pinned to this host
(`server.tomcat.remoteip.internal-proxies`), and a secure session cookie with `SameSite=Strict`
(`SESSION_COOKIE_SECURE=true`), so redirects and cookies keep the https scheme and no forwarded
header can speak for a client. The stack on this box is on a bridge, so the app sees the docker
gateway rather than the loopback and correctly distrusts the forwarded scheme — the site block
therefore upgrades a `Location: http://` on its way out and sets HSTS. The site block:

```
at.bluris.nl {
	encode zstd gzip
	redir / /autotrader/ 302
	reverse_proxy 127.0.0.1:8081 {
		header_down Location http:// https://
	}
	header Strict-Transport-Security "max-age=31536000"
}
```

The app keeps its own context path, so every URL is `https://at.bluris.nl/autotrader/…` (the bare
host redirects there). The TradingView webhook becomes
`https://at.bluris.nl/autotrader/webhook/tradingview` — update the alerts when the address changes.

The stack on this box is production as well as development, served through the same site. The
compose file is host-agnostic — it reads `SESSION_COOKIE_SECURE` and `AUTOTRADER_PUBLIC_URL` from
the repo-root `.env` with plain local defaults — and this box's `.env` sets `true` and
`https://at.bluris.nl/autotrader`, so the pages sign in over https and recovery mails link to the
public address. Its published port is bound to `127.0.0.1:8081`, not to the network: the API takes
HTTP Basic and the pages a session, and Caddy on the host is the only thing that should reach either.
On this box, where `.env` sets the secure cookie, a session cookie is never sent over plain http, so
the pages need the https address; with the local default (`false`), `http://127.0.0.1:8081` serves
the API and the pages alike — its Basic auth over http is why that port stays on the loopback.

### Checklist for the production host

1. DNS: an A/AAAA record for `at.bluris.nl` pointing at the host (done).
2. Caddy installed on the host, the site block above in its config, ports 80 and 443 open;
   `caddy reload` picks it up and Caddy obtains the certificate.
3. The box's `.env` carries `SESSION_COOKIE_SECURE=true` and
   `AUTOTRADER_PUBLIC_URL=https://at.bluris.nl/autotrader`; deploying is
   `./mvnw package -DskipTests -Ddocker.skip=true` followed by
   `docker compose -f docker-compose.dev.yml up -d --build` on the box.
4. Create the account: open `https://at.bluris.nl/autotrader/` and sign up (the first signup). Note
   the username and password somewhere safe — recovery needs the email address on the account.
5. Settings → **Mail**: store the SMTP server, then use "Forgot your password?" once and confirm the
   mail arrives.
6. The webhook's own key does not change; only its URL does.
7. Keep the app's own port (8081) closed to the internet: Caddy on the same host is the only public
   entrance, which is also what makes the forwarded-address handling trustworthy.
