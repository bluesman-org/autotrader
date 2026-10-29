# Strategy Engine Plan

Tracking document for the move to an in-app strategy engine, built on our own data and our own
indicators. TradingView is kept as a legacy fallback (Phase 6 records that decision). **Update this
file with every PR**: check off completed steps, add new follow-ups, and keep the status line
current.

**Status (2026-10-20):** Phases 1–5 complete (PRs #4–#12): candle store, indicators and rules,
backtester and sweep, REST API, in-app UI, Docker development stack, live engine (dry-run by
default, replay gate); the live series is anchored to a fixed start (PR #27) and the anchored
design is verified live — restarts kept the same anchor and the engine's trades matched a
from-anchor backtest exactly. The `.junie/` design notes live under `docs/` (PR #29) and 406
responses come from the advice (PR #28). Quality gates hardened (PR #13). Item 10's UI landed: the
live page (PRs #30, #33) and both tabs of the account history page — the engine's round trips
(PR #34) and the Bitvavo ledger (PR #36, fees shown in the currency the exchange charged, PR #37) —
with a hotfix for the history range queries (PR #35). The Bitvavo key now lives system-wide, outside
the bot configurations (PR #38). The history page's numbers read Dutch-style (PR #40), its filter
bars behave alike on both tabs and the ledger's type and status filters take several values (PR #44),
after the custom-range flow was fixed twice over (PRs #42, #43); the filters read as checkbox chips
and the narrow-viewport pass wrapped the nav too (PR #45), the ledger's timed refresh keeps the
table's scroll position and its open panels (PR #46), and the ledger reads as results only — a buy
shows no P&L, the summary's Unrealised is the open position and shows only when the range ends today,
with a Today button that ends the range at now (PR #47). The app now has an account: form sign-in
for the pages and HTTP Basic for the API, a first-account signup, password recovery by mail through
SMTP settings stored on the settings page, and an HTTPS deployment on `https://at.bluris.nl` behind
Caddy (PR #48). The trading-pair catalogue and the ledger reporting pass landed after that
(PRs #50–#56), and the TradingView offset timestamps and the unused live-strategy `botId` went with
PR #57. The live strategy management UI landed with PR #58, and the live order status guard with
PR #59. The live position close/reconcile and email alert path merged with PR #61 and is deployed;
the delete-path bug merged with PR #62 and was deployed, and the dry-run cleanup is complete. Web
Push notifications merged as PR #63 and are deployed; the phone test passed. The OpenAPI spec
catch-up and the manifest MIME fix merged as `89d8cee` (PR #64) and are deployed. The dry
exploration is running with five BTC-EUR strategies. The in-app Help page is in this PR (item 37).
TradingView is kept as a legacy fallback (Phase 6, decision of 2026-10-12; `docs/tradingview.md`);
next: compare the dry strategies, with the live trial user-triggered.

## Objective

- Consume Bitvavo market data and keep the candle history locally (MongoDB)
- Compute our own MACD and Parabolic SAR — no third-party TA code, no vendor values at runtime
- Define entry and exit rules independently per indicator, combined with AND/OR, parameterized
  from the UI
- Rank strategies with a train/validate backtester that sweeps entry×exit combinations and
  numeric parameter grids
- Trade live from the same rules, dry-run by default
- Keep the TradingView integration as a legacy fallback and revisit its removal once the engine
  trades live (Phase 6)

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

### Phase 4.5 — Docker development stack ✅ (PR #11)

- [x] New docker compose implementation for development (`docker-compose.dev.yml`: the app
      container plus the local Mongo, dev settings, no production credentials)
- [x] All Dockerfiles multi-platform (the image no longer pins `linux/arm64`; the platform is
      chosen at build time — native locally, `linux/arm64` in the Docker workflow's portability build)

### Phase 5 — Live engine ✅ (PR #12)

- [x] Bitvavo WebSocket candles (own keepalive/reconnect policy: `getTime` every 30 s, exponential
      backoff to 60 s, boundary-only reporting with the candle store as the authoritative source)
- [x] Evaluate rules on closed candles; dry-run by default (same rules, params and sizing as the
      backtester; live orders need an active bot configuration; failures are recorded, not thrown)
- [x] Persisted last-evaluated candle: no double-trade on restart, no stale replay (persisted
      before the order so a crash costs a trade, never duplicates one; the seed is never traded)
- [x] Replay gate: dry-run signals must replay identically on the same history
      (`LiveSignalReplayTest` asserts bar-by-bar equality of signals and indicator values)

### Phase 6 — TradingView: kept as a legacy fallback (decided 2026-10-12)

- [x] Keep the webhook path and its code: the strategy engine owning the strategy logic was the goal,
      and TradingView remains the only live-proven execution entry while the engine has traded in
      dry-run only (see the decision notes below)
- [ ] Decide on removal once the engine has traded live for a while — a dry-run trial first, then a
      small live one (the choice behind this Phase 6 decision; TradingView is a legacy fallback even
      though the objective once read "retire it")
- [ ] If TradingView becomes permanent, unify its order and position records with the engine's
      (`live_orders`/`live_positions` carry quantity, price and fees; the bot-centric ones do not)

## Follow-ups

1. ✅ **Coverage** — raised to the AGENTS.md rule (95% line / 100% method / 85% branch, POJOs and
   bootstrap classes excluded) and enforced in CI on every PR and main push
   (`.github/workflows/coverage.yml`, `./mvnw verify -Ddocker.skip=true`); includes the
   `GlobalExceptionHandler` null-cause fix
2. ✅ **Quality gates hardened** — boot-context test (`AutotraderApplicationTests`), SpotBugs with
   FindSecBugs at `verify` (accepted findings reasoned in `config/spotbugs-exclude.xml`),
   Dependabot for Maven/actions/Docker, and a Docker image + compose validation workflow
3. ✅ 405 responses carry the `Allow` header that Spring's default resolver sets from
   `getSupportedMethods()` (PR #22)
4. ✅ Warn on the backtest form when the initial capital cannot cover `amountPerBuy` plus its fee —
   a live hint under the simulation fields (blank fields fall back to the documented defaults),
   verified in a real browser; without it every entry skips as `INSUFFICIENT_BALANCE` and the run
   shows zero trades (PR #21)
5. ✅ Reconcile open orders after a crash between an order submission and its record — real orders
   are pre-written as `PENDING` with a client order ID and looked up in the exchange's history at
   boot; the position is synced and unreconcilable strategies are held back (v1: `filled` orders
   only) (PR #24)
6. ✅ Surface skipped buys (`INSUFFICIENT_BALANCE`) in the stored run and on the results page — the
   engine's metrics carry the count, runs store it (absent `—` for older runs), the ranked table
   has a Skipped column per side and the trades page a Skipped stat (PR #23)
7. ✅ Per-entry trade lists: the ranked table links each entry to a page (and
   `GET /api/backtests/{id}/entries/{n}/trades`) that recomputes it over the same candles and lists
   its train/validate trades and skipped signals (PR #19)
8. ✅ Offsite MongoDB backups in addition to `~/backups/autotrader` — every nightly dump is
   copied to Google Drive (mounted with `google-drive-ocamlfuse`), byte-verified and kept 90
   days offsite; a failed copy fails the nightly run loudly (PR #25)
9. ✅ Retire the `.junie/` design notes into `docs/`: `docs/tradingview.md` describes the legacy
   webhook path as built (plus the proposed pieces that were never implemented) and
   `docs/archive/user-system-design.md` keeps the unimplemented user-system design with a status
   header (PR #29)
10. ✅ UI improvements, starting from watching the live engine (spec agreed 2026-10-14; mock-ups to be
    approved first): a **live page** (`/ui/live`) with one card per strategy — running/connected and
    dry-run/real badges, the last error, how fresh the last evaluated candle is, the open position
    with the result "if closed now" after both fees, realised totals, the last few orders, Start/Stop
    buttons, and an in-place self-refresh that keeps the scroll position; times in local time,
    labelled. An **account history page** (`/ui/history`) with two clearly separated tabs: the
    engine's closed round trips — columns # (row number in the current view), Entry and Exit (each
    with date and time), Strategy (a stable colour per strategy), Type (dry/real), Price, Qty,
    Result; details expand inside the row; filters for strategy, market, type, result (wins/losses/all, 0
    counts as a win) and time (rolling 1h/2h/4h/8h/24h/3/7/14/30 days, calendar last week/last two
    weeks/last month/year-to-date/last year, and a custom start–end range; all by entry time); every
    column sorts on the underlying value, never the displayed text — and Bitvavo's account ledger:
    per-order rows with the order's total fee (`feePaid`) expanding into their fills, plus the raw
    transactions (deposits, withdrawals, staking, rebates) — modelled on the broker-app screenshots
    shared 2026-10-13. The live page landed (PR #30, its "last refresh" indicator in PR #33), the
    engine-trades tab (PR #34) and the Bitvavo ledger tab (PR #36); the follow-ups made the two tabs'
    filter bars share their mechanics and the ledger's type and status filters multi-value (PR #44) ✅
11. ⬜ Investigate upgrading to Spring Boot 4.1.1 with JDK 25 (issue #18): the runtime JDK bump
    belongs with the framework upgrade, which is why Dependabot's `eclipse-temurin` bump (PR #17)
    was closed
12. ✅ Duplicate `botId` on create returns a clear 400 (PR #4)
13. ✅ Inclusive boundary records for order/alert time-range queries (PR #4)
14. ✅ `target/autotrader.jar` refreshes on incremental builds — the antrun copy now overwrites
    (Ant skipped it because reproducible-build timestamps made every fresh jar look older than the
    previous copy, silently shipping stale code into the Docker image) (PR #20)
15. ✅ Handle `HttpMediaTypeNotAcceptableException`: a 406 with an empty body comes from this advice
    instead of the catch-all's 500 (the empty body is the only response that can always be written
    when the client's `Accept` rejects every producible type; flagged in the PR #22 review) (PR #28)
16. ✅ Make restores self-healing: deterministic client order IDs (derived from the strategy and a
    persisted sequence) so boot reconciliation can recover orders whose records were lost to a
    backup restore — a lost buy reopens the position, a lost sell closes it (PR #26)
17. ✅ Anchor the live candle series at a fixed start: the runner persists `seriesStartTime` on the
    first seed and never truncates the series afterwards, because Parabolic SAR's flip timing is
    start-sensitive (the 2026-10-13 rehearsal showed near-identical runs exiting on different
    candles). A backtest started at the anchor with `warmUp` set to the window length reproduces the
    engine's decisions bar for bar (it does not replay the seed range or downtime, so after a gap
    the position path can differ), and the convergence claims in `docs/live-engine.md` now carry the
    right scope (PR #27)
18. ⬜ Bound the live series' per-decision cost: it is append-only by design (the fixed anchor), so
    every closed candle re-evaluates the whole history — linear per decision, quadratic over uptime,
    with the retained heap growing the same way (about 8.8k candles a year on `1h`, 525k on `1m`).
    Carry the indicator state forward (both recursions stream) or find another way to bound
    `decide` while keeping the anchor semantics; the seed line logs the series length (flagged in
    the PR #27 review)
19. ✅ Make the TradingView webhook's timestamp contract honest: validation accepted a `Z`-less
    offset (`DateTimeFormatter.ISO_OFFSET_DATE_TIME`), but `saveAlert` then parsed with
    `Instant.parse`, so `2026-10-04T12:00:00+02:00` failed later as a 500 instead of the promised
    400. Validation now returns the parsed instant and the alert stores it, so both forms are
    accepted and normalised (PR #57)
20. ✅ Strategy management UI: create, edit, duplicate and delete live strategies from the UI,
    backed by the REST API's `PUT` and `DELETE` (and `?includeArchived`). A name is required; the
    startup initializer names documents that predate the field. The pair and interval stay editable
    until the first start and lock afterwards; Duplicate creates a variant. Delete is real only for
    a strategy that never started; one that has run is archived and stays in the history tab, with
    the button and confirmation saying which. Dry-run is chosen at creation and never switched — a
    real strategy is a copy whose name ends `-REAL` and whose anchor is fresh. Each ranked backtest
    row has a **Use live** link that prefills the form. Edits require the strategy to be stopped
    (PR #58)
21. ⬜ Keep the live page's reads flat as histories grow: the statistics line walks a strategy's
    whole round-trip history in memory on every 10-second refresh (the orders query is already
    bounded in the database). Aggregate count, sum, win count and the averages Mongo-side (flagged
    in the PR #30 review).
22. ⬜ Cover Mongo query execution in CI: the history page's range queries failed only at deploy —
    two conditions on one field cannot be written as a derived method name, and the second bound is
    rejected when the query document is built, which neither the mocked tests nor the context test
    (which mocks the startup runners, so CI needs no database) can see. PR #35 added a test that
    builds the repository proxies and materializes each query document without a database, so a
    malformed query now fails in CI; executing the queries against real documents still needs a
    Mongo service in the coverage workflow (flagged after PR #34)
23. ⬜ Keep the ledger's reads flat as the account grows: the ledger's ten-second refresh re-reads
    the market's whole buy/sell history from the account's start on every tick, because the
    average-cost walk needs it; a long-lived account makes that many pages. Cache the walk and
    extend it with the new fills, or bound the look-back with a stated rule (flagged in PR #36)
24. ✅ Move the Bitvavo key out of the bot configurations: the engine and the ledger sign with one
    system-wide credential, stored encrypted and managed on `/ui/settings` (or through
    `/api/bitvavo-credentials`); a startup copy takes the key an installation already stored inside
    a bot configuration, so nothing has to be re-entered (PR #38)
25. ✅ Drop the live strategy's `botId`: the engine reads the system-wide key now, so the field was
    historical only. It is removed from the model, the create request and the docs; no stored
    strategy carried it (the running database answered 0 to
    `db.live_strategies.countDocuments({bot_id: {$exists: true}})`, and the 2026-10-20 code review
    recorded that check), and the startup key copy now reads the first active bot configuration
    with a key, logging the choice and warning when several qualify (PR #57)
26. ✅ The ledger reads as results only: a sell shows what it realised at average cost, a buy shows
    none — the sell that closes it carries it — and the summary's Unrealised is the open position at
    the current price, shown only when the range ends today (a line says why when it does not); a
    Today button beside the end date sets the range's end to now, so the figure is one click away
    (PR #47)
27. ✅ Sign-in and password recovery: one account (`app_users`), form login for the pages and HTTP
    Basic for the API, a first-account signup that closes afterwards, an SMTP server stored on the
    settings page (and through `/api/mail-settings`) for a single-use, hashed, expiring recovery
    mail, an authenticated password change, and the HTTPS deployment for `https://at.bluris.nl`
    behind Caddy (`docs/security.md`) (PR #48)
28. ✅ Trading-pair catalogue: the pairs the account traded on Bitvavo are discovered from
    `/account/history` (a handful of calls, not one per market), stored in `markets` and managed on
    `/ui/settings` and through `/api/markets`; enabled pairs feed the strategy forms and the
    ledger's market picker, and a live strategy's pair stays visible even when disabled. A pair is
    disabled, never deleted, and a discovery never re-enables one.
29. ✅ Ledger reporting: the orders table's columns sort on their underlying values; the all-pairs
    view sums the filtered orders per market (orders, buy/sell volume, euro fees, realised); the
    raw view filters on the exchange's transaction types and sums them per type and currency, so
    staking income and external transfers are visible, with the transfer address when Bitvavo
    reports one. The raw Type filter also picks up undocumented types the API returns, and the page
    notes that the orders are the spot account's and margin is not exposed by the Exchange API.
30. ✅ Accept only filled order responses in the live engine: `applyFill` used to mark every
    accepted `POST /order` response `FILLED` without reading its `status`, so a `partiallyFilled`
    or `canceled*` response would open or close the position at the estimated size and the boot
    reconciliation (which revisits only `PENDING`/`FAILED` rows) would never correct it. Only a
    `filled` status settles the row; a status that can still fill (or one Bitvavo adds later)
    leaves it `PENDING` and blocks further decisions until reconciliation. An ambiguous failure
    carries the same awaiting-reconciliation marker, so a timeout cannot hide an accepted order;
    a terminal non-fill, or a complete history proving the exchange never accepted it, settles as
    `FAILED` with the exchange status in the error (PR #59)
31. ✅ Ledger periods follow the order's own moment: the orders tab read and dated every order by
    when it was placed — the field Bitvavo's order query filters on — so a take-profit armed on
    9 Oct and filled on 21 Oct appeared under 9 Oct *and* was missing from the 21 Oct range
    entirely, while that day's summary showed nothing even though the money moved. The read is now
    bounded at the period's end only and each order is filtered on its last fill (or, while it has
    never traded, its placement — its cancellation or expiry once it is gone); the row dates by
    that moment with the placement underneath when the two days differ, the date sort follows it,
    and the summary counts it there. An order that fills in pieces lands in the period of its last
    fill, and a read that hits the exchange's 1000-order limit says so in the summary (PR #60)
32. ✅ Close a live position and reconcile a manual close: a stopped strategy can close its position
    from the app — a simulated sell for a dry run, a market sell for a real one — with the current
    bid and the estimated result after both fees, and a warning before the action. A manual sell
    made on Bitvavo's own app can be attached by picking the verified exchange order; the app
    records an external `FILLED` row, closes the position at the exchange's average fill and
    prorates a larger sell, while refusing a partial one. Real fills, failures, orders left
    `PENDING` and strategies held back by reconciliation email the account through a bounded daemon
    executor, with a test button on the settings page (PR #61; the dry close acceptance exposed the
    delete-path bug below)
33. ✅ Never delete a live strategy with an open position, and archive one that has any history: the
    delete path checked the open position only after the never-started branch, so an old 1m
    strategy without start markers was deleted while its dry position stayed open. The open
    position is now checked first; `hasStarted` also honors `lastEvaluatedOpenTime`; and the delete
    path archives a strategy with any orders or positions. The live card treats an open or closed
    position as started. The two affected strategies were restored for the cleanup: the orphaned
    dry position was closed and archived, and the other history label was restored (PR #62,
    deployed)
34. ✅ Web Push notifications next to email: the live engine's real fills, failures, pending orders
    and held-back strategies also reach a subscribed browser, independently of SMTP. The settings
    page registers the service worker, asks for the permission, stores the browser's subscription
    and offers a test push per device. The VAPID pair is generated once and its private key kept
    encrypted in `push_settings`; subscriptions live in `push_subscriptions`, an unusable endpoint
    (403, 404 or 410) is removed, and the service worker, manifest and icon are the only public push
    assets. Android Chrome works in the browser; iOS needs the site on the home screen (PR #63,
    deployed; locked-screen test passed)
35. ✅ Regenerate the committed OpenAPI spec after #63: it now includes the push endpoints, the #61
    close/reconcile endpoints and the #62 delete description, which were missing from the committed
    copy. It is generated with the clean 8081 startup procedure. The two test endpoints have
    explicit operation IDs (`sendTestAlert`, `sendTestPush`), and `OpenApiSpecDriftTest` boots the
    app on a random port and compares `/v3/api-docs` with the committed JSON, so a future annotation
    change fails CI when the spec is stale.
36. ✅ Serve the web app manifest as `manifest.json` instead of `.webmanifest`: Spring's static
    resource handler served the latter as `application/octet-stream`, which browsers may reject;
    `.json` is served as `application/json`. The link, the security open path and the docs follow
    the new name, and a request-level test pins the anonymous 200 and the JSON content type.
37. ✅ In-app help page: a single signed-in `/ui/help` operator guide with a contents list, short
    sections for the live engine, strategies, positions and history, alerts and push, settings and
    security, troubleshooting and a glossary. The backtest section explains train/validate, every
    result column, the simulation assumptions and worked examples in plain language. The Help link
    is in the navigation, with contextual links from the backtest, live, close/reconcile and
    settings pages. Static page; no model or repository. Tests cover the page, the sign-in rule and
    the contextual links (this PR).
38. ✅ Stabilize the live-runner anchor test: its bounds were on the wrong side of the start time,
    so a slow CI run failed even though the anchor was correct. It now checks the anchor between
    `beforeStart - window` and `afterStart - window` (this PR).
39. ✅ Stop line-ending normalization from dirtying `mvnw.cmd`: `.gitattributes` declared `*.cmd` as
    `text eol=crlf`, but the wrapper's batch file is committed with CRLF, so the clean filter
    rewrote it to LF in the index and every checkout reported it as modified, while `git add -A`
    would have rewritten the file. The rule is now `*.cmd -text`, which keeps the committed bytes
    and leaves a fresh clone clean (this PR).

Done items carry ✅, open items carry ⬜; the list is numbered so items can be referenced by number
(e.g. "item 7"). Task-list checkboxes cannot be combined with ordered lists on GitHub: the checkbox
suppresses the list marker, so the numbers vanish.
