# Strategy Engine Plan

Tracking document for replacing TradingView with an in-app strategy engine, built on our own
data and our own indicators. **Update this file with every PR**: check off completed steps,
add new follow-ups, and keep the status line current.

**Status (2026-10-11):** Phase 1 complete (PR #4), coverage gate in place (PR #5), indicators
pinned to the reference fixtures (PR #6), rules in (PR #7), backtest engine and sweep in (PR #8),
REST API in (PR #9), in-app UI in (PR #10). Next: Phase 5, the live engine.

## Objective

- Consume Bitvavo market data and keep the candle history locally (MongoDB)
- Compute our own MACD and Parabolic SAR — no third-party TA code, no vendor values at runtime
- Define entry and exit rules independently per indicator, combined with AND/OR, parameterized
  from the UI
- Rank strategies with a train/validate backtester that sweeps entry×exit combinations and
  numeric parameter grids
- Trade live from the same rules, dry-run by default
- Retire the TradingView integration once the engine covers its role

## Decisions

- Built inside the existing Spring Boot app (Freqtrade evaluated and rejected)
- Indicators implemented from the published definitions, with conventions matched to TA-Lib's
  documented bootstrap so frozen test vectors compare exactly (TA-Lib values in tests only)
- Simulation contract: decisions on closed candles only; fills at next-candle open; fee per side
  configurable, defaulting to the account's **taker** rate for market orders (0.20% at the current
  tier — live-verified via `GET /account/fees`; the 0.10% maker rate applies only to limit orders);
  fixed EUR amount per buy (minimum €5); one open position; 200-candle warm-up; skipped signals
  (insufficient balance) are recorded, not simulated; a persisted last-evaluated candle prevents
  double-trading on restart and stale replay after downtime; deterministic outputs; sweeps capped
  at roughly 5,000 combinations
- Strategy selection by train/validate split
- Strategy intervals are limited to those the live WebSocket supports (1m–1d), so a backtest and
  the live engine always see the same candles
- Candle history kept forever; backups cover bots/orders/positions/signals only (candles are
  re-downloadable)
- Local MongoDB runs in Docker on this machine

## Phases

### Phase 1 — Candle history store ✅ (PR #4)

- [x] `CandleInterval`, `Candle` document, `CandleRepository` — unique per (market, interval, open time)
- [x] `CandleHistoryService`: on-demand backfill, head/tail gap fill, 1440-per-request pagination,
      closed-candle capping, cancellation semantics, per-market serialization
- [x] `BitvavoApiClient.getPublic` on a timeout-bounded `publicRestTemplate` (signed trading client
      unchanged on purpose)
- [x] `MarketFormat` normalization and validation
- [x] `MongoIndexInitializer`: explicit indexes, duplicate-`botId` guard, legacy index migration;
      `MongoConfig` honors `spring.data.mongodb.auto-index-creation`
- [x] Local MongoDB stack (`docker-compose.local.yml`, init script) and backup script
- [x] Live verification: 100 days of BTC-EUR 1h backfilled and idempotent on re-run, index
      migration, boundary-inclusive time-range queries

### Phase 2 — Indicators and rules ✅ (PRs #6–#7)

- [x] MACD (published definition, TA-Lib-compatible bootstrap) — bit-exact against the frozen fixtures
- [x] Parabolic SAR (published definition, TA-Lib-compatible bootstrap) — bit-exact against the
      frozen fixtures
- [x] Indicator verification: written spec (`docs/indicators.md`), hand-worked linear case,
      degenerate cases (flat, oscillating, minimum length), frozen TA-Lib fixtures
      (`src/test/resources/indicators/`)
- [x] Rule model: 10 conditions across both indicators, independent entry/exit sets combined with
      ALL (AND) or ANY (OR), raw per-bar signals — specified in `docs/rules.md`
- [x] Parameter model shared by backtest, UI and live engine (`MacdParameters`,
      `ParabolicSarParameters`, eager validation)

### Phase 3 — Backtester and API ✅ (PRs #8–#9)

- [x] Deterministic simulation engine: closed-candle decisions, next-open fills, fixed EUR per
      buy, fee per side (default 0.20% taker — live-verified), skipped-signal records, forced
      final close; specified in `docs/backtest.md`
- [x] Entry×exit matrix sweep plus numeric parameter grids, ~5,000-combination cap
- [x] Train/validate split ranking (optimise on the earlier portion, verify on the held-out newer
      portion), selectable ranking metric, deterministic outputs
- [x] REST API for running backtests and reading ranked results (`POST /api/backtests`,
      `GET /api/backtests/{id}`, `GET /api/backtests`; runs stored in `backtest_runs`)

### Phase 4 — In-app UI (Thymeleaf) ✅ (PR #10)

- [x] Configure strategies and parameters (`/ui/backtests`: market, interval, UTC range, simulation
      settings, indicator candidate grids, entry and exit rule sets with ALL/ANY)
- [x] Run backtests and browse ranked results (synchronous run with inline validation errors, the
      ranked table with train and validate metrics side by side, recent runs, welcome-page link)

### Phase 5 — Live engine

- [ ] Bitvavo WebSocket candles (own keepalive/reconnect policy)
- [ ] Evaluate rules on closed candles; dry-run by default
- [ ] Persisted last-evaluated candle: no double-trade on restart, no stale replay
- [ ] Replay gate: dry-run signals must replay identically on the same history

### Phase 6 — Retire TradingView

- [ ] Remove the webhook path and TradingView-specific code once the engine covers its role

## Follow-ups

- [x] **Coverage** — raised to the AGENTS.md rule (90% line / 100% method, POJOs and bootstrap
      classes excluded) and enforced in CI on every PR (`.github/workflows/coverage.yml`,
      `./mvnw verify -Ddocker.skip=true`); includes the `GlobalExceptionHandler` null-cause fix
- [ ] 405 responses should carry the `Allow` header that Spring's default resolver sets from
      `getSupportedMethods()` (flagged in the PR #9 review; not a regression, restore it when the
      handler is next touched)
- [ ] UI improvements (TBD)
- [ ] Surface skipped buys (`INSUFFICIENT_BALANCE`) in the stored run and on the results page — a
      run where half the entries were skipped currently looks identical to one with none, and that
      matters more once Phase 5 trades live
- [ ] Offsite MongoDB backups (e.g. S3) in addition to `~/backups/autotrader`
- [ ] Optionally move the remaining `.junie/` design docs under `docs/`
- [x] Duplicate `botId` on create returns a clear 400 (PR #4)
- [x] Inclusive boundary records for order/alert time-range queries (PR #4)
