# Autotrader

See AGENTS.md for the full project structure, details, and API documentation, and
[docs/security.md](docs/security.md) for the sign-in, password recovery and HTTPS setup.

The app holds one account: a fresh installation starts at `/signup`; the pages then sign in with a
form, and the JSON API takes HTTP Basic with the same account. Production runs on
`https://at.bluris.nl/autotrader/` behind Caddy, which terminates TLS and proxies to the app.
