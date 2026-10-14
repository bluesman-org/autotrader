# TradingView webhook (legacy path)

TradingView is kept as a legacy fallback (Phase 6 decision of 2026-10-12): the in-app strategy
engine (`docs/live-engine.md`) is the primary path, and this webhook stays only because it is the
live-proven execution entry until the engine has traded live. This page describes the path as
built — reconciled with the code — and lists the pieces the original 2026-10-04 design note
proposed that were never built.

## The endpoint

- `POST /webhook/tradingview` (`TradingViewWebhookController`), body
  `{botId, ticker, action, timestamp, dryRun}`:
  - `ticker` must match the bot's configured pair and be EUR-quoted;
  - `action` is `buy` or `sell`; `timestamp` is `yyyy-MM-ddTHH:mm:ssZ`;
  - `dryRun: true` runs the whole path except sending the order to Bitvavo; the recorded order
    carries a `dry-run-...` exchange id (the strategy engine has its own dry-run).
- **Authorization**: alerts from TradingView's published webhook IPs (52.89.214.238,
  34.212.75.30, 54.218.53.128, 52.32.178.7) are accepted; any other caller must be localhost and
  present the bot's webhook key in `X-API-KEY`, whose SHA-256 hash is stored on the bot
  (`webhookKeyHash`). Everything else answers 401.
- **Responses**: 200 on success; 400 for client errors (missing field, bad timestamp, unknown or
  inactive bot, ticker mismatch, unsupported ticker, missing operator id, invalid action); 401 for
  the auth cases; 500 for processing failures.

## What it does

- Every alert is stored in `tradingview_alerts`; orders and their failures go to the bot-centric
  `orders` collection with an `error_message`, and positions to `positions`.
- A buy spends the bot's whole EUR balance (rounded down to the market's notional decimals); a
  sell the whole asset balance (rounded down to the quantity decimals). Both refuse amounts below
  5 EUR. The engine's own buys use its fixed amount instead — the two paths only share the
  exchange client and the bot configuration.
- Bot API keys and secrets travel encrypted (AES-256-GCM with `encryption.master-key`); a
  `key_version` field is reserved for rotation.
- These bot-centric records carry no quantity, price or fee. If TradingView ever becomes
  permanent, unifying them with the engine's `live_orders`/`live_positions` is the Phase 6 item.

## Proposed but never built

The original design note (`.junie/design.md`, 2026-10-04) also proposed rate limiting (429 per
bot), automatic master-key rotation, per-bot data-encryption keys (envelope encryption), audit
alerts for failed authentication, and application-level HTTPS enforcement. None of it is in the
code; it is recorded here so the gap is visible if this path becomes permanent.
