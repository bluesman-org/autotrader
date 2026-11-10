# Test-quality gates: fix the hollow tail, prevent the drift

Two failure modes, from the `ai-slop` investigation:

1. **Assertions derived from the implementation instead of the requirement.** The test inherits the
   code's beliefs, including its mistakes, and can only fail if the code changes — never because
   the code is wrong.
2. **A red test resolved by weakening it.** The suite goes green, the alarm disappears, and the
   regression becomes permanent and invisible.

## Premise

The cheap checks below found no evidence of widespread slop — but they cannot detect either failure
mode. They detect shallow *shapes*. An assertion copied from a method body is textually
indistinguishable from a good one, and a commit that replaces `assertEquals` with `assertNotNull`
while adding an unrelated test is net positive on assertion lines.

So this plan does not claim the suite is clean. It claims: **no evidence from the cheap checks, and
no mechanism that would produce evidence.** Mutation testing in one package supplies the first, and
the rules in step 1 supply the second.

The strongest existing protection is not a rule but a fixture discipline: `IndicatorFixtures` plus
the frozen indicator tests (`MacdTest`, `ParabolicSarTest`, `StrategyEvaluatorTest`) pin values
against external references. The behavior-stating method names help, but they are a convention, not
a mechanism — `CandleStreamFactoryTest.createsAStreamWithTheConfiguredPolicy` asserts only
non-null and never checks the policy.

## Audit, with counts verified against the tree

Scope: 100 test classes plus `IndicatorFixtures`, 937 test methods (936 `@Test` plus one
`@ParameterizedTest` in `LedgerServiceTest`). The grep commands are recorded in step 5 so the
numbers stay reproducible.

| Check | Verified count | Status |
| --- | --- | --- |
| Boolean tautologies | 0 | Clean |
| `@Disabled` / `@Ignore` | 0 | Clean — note `MongoQueryExecutionTest` is guarded by `@EnabledIfEnvironmentVariable`, not disabled |
| Tests with no assertion at all | 3 | Real defects, see step 3 |
| `assertNotNull` as the only assertion | 5 | 4 structural, 1 behavioural (step 3) |
| `verify(...)` with only `any()` | 2 | Real but mild — `LiveUiControllerRenderTest:301,357` |
| `assertThrows(Exception\|RuntimeException.class)` | 11 | Not the wrong type — but under-specified, see below |
| `Thread.sleep` | 1 | Not a signal: inside a bounded `await(...)` helper with a `fail()` deadline |
| Commits with net assertion loss, last year | 2 | Both genuine refactors (`dbaa05c`, `00152bc`) |

An earlier draft of this plan claimed the 11 `assertThrows` cases were over-broad. They are not:
`TradingService` wraps everything in `RuntimeException` (`TradingService.java:277`),
`EncryptionService` in `RuntimeException` (`:63`, `:101`), `BitvavoAuthenticationService` at `:89`,
and `LiveAlertService.sendTest` propagates its dependency's `RuntimeException`. The type each test
expects is the type the code actually throws.

But the assertion is still under-specified, which is a separate problem. A null-pointer bug inside
the method satisfies `assertThrows(RuntimeException.class)` exactly as well as the intended
rejection, so the test cannot tell a working path from a crash. That is not a wrong type; it is a
missing cause. See step 3, batch 2.

The actionable set is **20 tests in two batches**: nine whose assertion is hollow, and eleven whose
assertion a wrong exception would satisfy. An earlier draft said ~22 and then ~9; both were wrong,
for opposite reasons.

## Steps

- [ ] **1. Write the rule into `AGENTS.md`** — what a test must state, the banned shapes, the
      red-test policy.
- [ ] **2. Add a shape-check script to CI** — deterministic detection of the banned shapes, with a
      reviewed allowlist.
- [ ] **3. Fix the verified defects** — two batches, no dependency on PIT.
- [ ] **4. Add the scoped PIT profile** — two-stage pilot; needs `JAVA_HOME=25`.
- [ ] **5. Record the measurements in `docs/test-quality.md`** — PR #79 has landed, so the measured
      package is now stable enough to measure.
- [ ] **6. Add the ratchet job** — weekly, on `strategy.rule` only, compared against a pinned
      scope.
- [ ] **7. Add the red-test rule to the reviewer** — it already reviews this repo; make it explicit.

Steps 1–3 are independent and can ship first. Steps 4–6 form a chain and can stall as a unit.

## Step detail

### 1. Write the rule into `AGENTS.md`

File: `AGENTS.md`, the `Testing` block.

**What a test must state.** One claim about observable behavior, named in the method name, with an
expectation that is independently justifiable — from `docs/security.md`, `PLAN.md`,
`docs/rules.md`, `docs/live-engine.md`, the Bitvavo contract, or a recorded worked example. Where
no written spec exists, the test records the assumption rather than silently deriving it from the
code. Characterization tests are legitimate; *unacknowledged* characterization tests are the
failure mode.

**The banned shapes**, each with the reason:

- `assertNotNull` as the only assertion on a method with branching logic.
- A test with no assertion at all. "No exception means success" is not an assertion.
- `assertThrows(Exception.class)` where the production code can throw an unrelated exception of the
  same type — assert on the message or the cause, so an NPE cannot satisfy the test.
- `any()` on an argument that *is* the contract. The argument is the assertion.
- Asserting a value the test itself set on the object under test.

**The red-test policy.** A failing test presumes the production code is wrong. Changing an
assertion requires a stated reason — commit message or PR — naming what the original assertion
wrongly assumed. No stated reason, no weakened assertion.

**Reconcile the coverage rule.** The section currently reads as a bare target. Add:

> Coverage is a floor, not a target. A test that cannot fail is a defect of the same severity as a
> missing one. Never add a test whose only purpose is to move the number.

Also fix a stale fact while in the file: it says JUnit 5; the suite resolves to JUnit Jupiter
**6.0.3** via `spring-boot-dependencies:4.1.2`.

### 2. Add a shape-check script to CI

New: `scripts/check-test-shapes.sh`, called from `.github/workflows/coverage.yml`.

Narrow, deterministic rules only — the ones with no judgement call:

- an `@Test` method containing no `assert*`, `assertThat`, `verify`, `verifyNoInteractions`,
  `andExpect`, `andDo`, or `fail(`;
- `assertThrows(Exception.class)` / `(RuntimeException.class)` with no assertion on the cause, so an
  unrelated crash of the same type would satisfy it.

Two rules, deliberately. An earlier draft had a third keyed on test names containing `_with` or
`_when`; it is dropped as the fragile one — it would fire on honest names and be disabled within a
month. Keep the rules few: a check that produces false positives does not survive.

Allowlist with reasons, same convention as `config/spotbugs-exclude.xml`. Keep the rules few: a
check that produces false positives gets disabled within a month. This is deliberately *not* a
custom Maven plugin or an `any()` ban — those are the brittle parts.

Why this and not prose alone: the audit was itself a set of greps, so the same greps can run in CI.
`AGENTS.md` explains the rule; this enforces the subset that is mechanically decidable.

### 3. Fix the verified defects

Twenty tests, in two batches. Every fix has one of three outcomes, in this order of preference:
strengthen the assertion; change the production contract and the test together; delete the test if
it is provably redundant.

**A comment is not an outcome.** An earlier draft allowed "a comment stating why the weak assertion
is the contract". That is an escape hatch that blesses defects — it would let a test that passes on
a `NullPointerException` be marked correct.

**Batch 1 — the hollow assertions.**

| # | Test | Problem | Fix |
| --- | --- | --- | --- |
| 1 | `BitvavoAuthenticationServiceTest.initMac_initializesMacCorrectly:145` | No assertion; the comment says "no exception means success" | Assert the initialized `Mac` produces the expected HMAC for a known vector |
| 2 | `BitvavoCredentialsInitializerTest.run_isANoOpWhenAKeyIsAlreadyStored` | No assertion | `verify(..., never())` on the write, or assert the stored value is unchanged |
| 3 | `LiveStrategyRunnerTest.alreadyEvaluatedCandlesAreSkippedWithoutTouchingTheStore` | No assertion | Assert the store is untouched (`verifyNoInteractions`) and the decision count |
| 4 | `PasswordResetServiceTest.aClaimOnTheMintedTokenStopsTheRestore:304` | `assertNotNull` on a value the test itself set; behavioural test, structural assertion | Assert the observable outcome — the password is unchanged |
| 5 | `CurlLoggingInterceptorTest.defaultConstructor_createsItsOwnLogger:53` | `assertNotNull(new ...)`; the constructor is also exercised by the context test | Delete, after confirming `AutotraderApplicationTests` covers it |
| 6 | `MongoConfigTest.mongoClient_buildsAClientFromTheSettings` | `assertNotNull` only | Assert the client's settings reflect the configured URI |
| 7–8 | `CandleStreamFactoryTest` (×2) | `assertNotNull` only; the name claims a policy | Assert the policy value, not the stream's existence |
| 9–10 | `LiveUiControllerRenderTest:301,357` | `create(any())`, `update(eq("..."), any())` | Capture and assert the form fields the controller received |

**Batch 2 — the exception tests.** The type each one expects is correct; the cause is missing. An
assertion on the cause is what distinguishes the intended rejection from a crash inside the method.

| # | Test | Fix |
| --- | --- | --- |
| 11–13 | `TradingServiceTest` (×3) | Assert the injected API failure is the exception's cause |
| 14–15 | `BitvavoAuthenticationServiceTest` (×2) | `assertInstanceOf(InvalidKeyException.class, ex.getCause())` — the method catches `NoSuchAlgorithmException \| InvalidKeyException` at `:88` |
| 16 | `EncryptionServiceTest.decrypt_withInvalidCiphertext` | Assert the cause is a decoding or decryption failure, not an NPE |
| 17–18 | `LiveAlertServiceTest` (×2) | Assert the cause, or narrow to `IllegalStateException` where the Javadoc at `:163-164` documents it |

**Three of the eleven are not test fixes.** `encrypt(null)`, `decrypt(null)` and `decrypt("")` all
throw a `NullPointerException` or an argument error that `catch (Exception e)` at
`EncryptionService:62-63` and `:100-101` converts to `RuntimeException("Encryption failed")`. No
assertion can distinguish that from a real encryption failure, because the service erased the
difference. These need `Objects.requireNonNull` / blank-input validation, and then a narrowed test —
a production change with its own PR, listed in out-of-scope below.

Acceptance: `./mvnw -B verify -Ddocker.skip=true` passes. Run `verify`, not `test` — the JaCoCo
`check` and the SpotBugs gate only run at `verify`.

Note when fixing test 5: `METHOD COVEREDRATIO` is `1.00`, so deleting a test can only be safe when
another test still invokes the method. Confirm with the JaCoCo report before deleting.

### 4. Add the scoped PIT profile

File: `pom.xml` — `<profile><id>mutation</id>`.

Pin the version; do not float it. `org.pitest:pitest-maven` needs to be at least 1.30.1 for Java 25
bytecode (ASM 9.11).

- **Run it as `./mvnw -B -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage`.**
  Invoking `pitest:mutationCoverage` alone fails on a clean checkout: the goal has no
  `executePhase` and `target/classes` does not exist yet.
- `-Dpit.dryRun=true` first. It verifies test discovery in seconds without mutating anything.
- `targetTests` = the **whole** test tree, not the matching package. Tests outside the package
  exercise these classes (`LiveUiControllerRenderTest` drives `LiveStrategyService`); a narrow
  selection reports `NO_COVERAGE` for mutants that a real test does cover, which would be misread
  as a weak suite.
- `threads=2`, `timestampedReports=false`, `outputFormats=HTML,XML`,
  `mutationThreshold=0`, `coverageThreshold=0` for the measuring run.

**Two stages, in this order:**

1. `nl.jimkaplan.autotrader.strategy.rule` — 11 classes, 615 LOC, 31 tests. Validates the setup in
   minutes, and its result is the one that gets a recurring gate.
2. `nl.jimkaplan.autotrader.strategy.service` — 21 classes, 6,760 LOC, 319 tests. The slow one, and
   a measurement rather than a gate: its output is a list of tests worth reading.

**The actual compatibility risk is JUnit 6, not Java 25.** The suite is JUnit Jupiter 6.0.3;
`pitest-junit5-plugin` (latest 1.2.3) was built against `junit-platform-launcher` 1.9.x and predates
JUnit 6. If PIT cannot discover tests, that is the cause — add `junit-platform-launcher` at the
resolved version, or process only the `strategy.rule` package as a proof of concept.

**Time-box: one day.** If it fights, stop: ship steps 1–3, keep the PIT work as a documented
blocker, and drop steps 5–6. Do not half-install a ratchet with no valid measurement.

Operational note: `./mvnw -v` on the development box reports **Java 21**, while `pom.xml:31` targets
25. Any run needs `JAVA_HOME` pointing at a JDK 25, or it fails to compile before PIT starts. Record
that in the docs.

`strategy.service` needs no MongoDB — there are no `@SpringBootTest` or `@DataMongoTest` classes in
that package. `JdkPushSenderTest` does open a loopback `HttpServer` and one case waits on a
one-second timeout; it will run per mutant and inflate runtime.

### 5. Record the measurements in `docs/test-quality.md`

New file, alongside `docs/security.md`. **Measure on top of `c75a333` or later.** PR #79 changed
`LiveTradingService` (97 lines) and `LiveTradingServiceTest` (81 lines), both inside
`strategy.service`, and a measurement from before that landed would describe code that no longer
exists.

Two runs, recorded separately:

- **`strategy.rule`** — the fast one, and the only one that gets a recurring gate. Its numbers are
  the floor the step 6 job enforces.
- **`strategy.service`** — measured once, on demand. Its numbers are a triage backlog, not a gate:
  the package changes in most PRs, so a weekly alarm there would fire on honest refactoring and be
  ignored within a month.

Contents:

- What mutation testing measures that coverage does not, in three sentences.
- The exact reproducible command, including `JAVA_HOME`.
- The measurements table: date, **commit SHA**, packages, mutation score, test strength, mutants
  killed / survived / no-coverage.
- The pinned scope: the exact `targetClasses` / `targetTests` / excluded methods, in a
  machine-readable form the step 6 job reads. Prose is not enough — narrowing the scope is the
  easiest way to raise the score.
- The shape-check rules and their allowlist.
- The audit greps that produced this plan's counts, so they can be rerun.
- Any mutant accepted as equivalent — `class#method:line` plus an argument. Cap the count.
  `NO_COVERAGE` is never equivalent; it means the test is missing.

**Define both metrics, because they answer different questions.** PIT's *mutation score* is
`killed / all mutants` and includes `NO_COVERAGE`. *Test strength* is
`killed / (killed + survived)`. Test strength is the quality signal; mutation score is what a
release gate cares about. The ratchet uses test strength so that adding an untested class cannot
lower it.

### 6. Add the ratchet job

New: `.github/workflows/mutation.yml`, scoped to `strategy.rule`.

- `schedule` weekly plus `workflow_dispatch`. Not on PRs: the package is small and stable, so a
  weekly check is enough, and a PR job would cost more than it catches.
- Compares against the pinned scope and measurement from step 5, not against a rounded percentage.
  PIT's `mutationThreshold` is an integer by default; a drop of several mutants can hide inside the
  same integer. Either set `thresholdPrecision` or compare the XML report in a small script.
- Fails if any pinned class disappears from the report — scope reduction is a silent ratchet bypass.
- Uploads the HTML and XML as artifacts, so a survivor is triageable instead of abstract.
- Latest action majors: `actions/checkout@v7`, `actions/setup-java@v6`.

`strategy.service` gets no gate. Measure it when the code there settles, and revisit if the runtime
turns out to be under about twenty minutes.

### 7. Add the red-test rule to the reviewer

The reviewer pipeline already covers this repo unconditionally —
`~/.config/openchamber-pr-reviewer/config.bluesman-org.json:5`, `"unconditionalRepos": ["autotrader"]`.
This is not conditional, as an earlier draft assumed.

State it plainly: the review is **advisory** (`COMMENTED`, non-blocking). Adding the red-test rule
there makes it visible on every PR; it does not make it enforced. Enforcement is steps 2 and 6. The
rule text lives outside the repo, so changing it is a config edit plus a server restart — agent
definitions are cached.

## Risks

| Risk | Mitigation |
| --- | --- |
| PIT cannot discover JUnit 6.0.3 tests | `dryRun` first; pin the launcher, or stop at `strategy.rule` |
| PIT runtime too large on `strategy.service` | Pilot `strategy.rule` first; `threads=2`; keep the measuring run manual |
| Java 21 is the local default | Export `JAVA_HOME` for JDK 25; document it |
| Shape-check false positives | Narrow rules plus a reviewed allowlist; delete a rule that fires wrongly |
| Fixing tests breaks the coverage gate | Branch has ~1.5 points of headroom (86.52% vs 85% floor); acceptance is `verify` |
| Ratchet gamed by narrowing scope | Pinned machine-readable scope; fail when a class disappears |
| Equivalent-mutant escape hatch | `class#method:line` plus argument, capped, `NO_COVERAGE` excluded |

## Out of scope

- A whole-module or per-PR mutation gate. Too slow; red on day one.
- Changing or removing the coverage gate. The floor is useful; the problem is treating it as the
  goal.
- Rewriting the existing suite. Nothing in the audit justifies it.
- `EncryptionService` input validation: `encrypt(null)`, `decrypt(null)` and `decrypt("")` should
  fail as bad input, not as "Encryption failed". A production change, and the precondition for the
  three deferred exception tests. File it separately.
- A custom Maven plugin or an `any()` ban. The shape script covers the decidable subset.

## Decisions

1. **`METHOD COVEREDRATIO` stays at 1.00.** It is the only counter that catches code nobody calls —
   an unused order path or reconciliation branch — and it is currently met with no padding pressure
   visible: 937 tests, three without an assertion, all three named in step 3. Lowering it would
   trade a real reachability check for a hypothetical one. Revisit only if the shape-check allowlist
   grows past about ten entries, which would mean the rule is pushing tests into existence.
2. **The recurring gate covers `strategy.rule` only.** `strategy.service` is measured once and
   treated as a backlog, because the last four PRs all touched it; a weekly alarm on a package under
   active refactoring is noise, not a ratchet. Revisit if its measurement run turns out to be quick.
3. **The shape check enforces two rules, not three.** The naming-based rule is dropped as fragile.
4. **`EncryptionService` input validation is a separate change**, since it alters production
   behaviour. It is the precondition for three of the eleven exception tests.

## Status

Branch `feature/test-quality-gates`, cut from `main` and rebased onto `b465519` after PR #79
(`c75a333`) merged. The branch name lacks an issue number, which the repository convention asks
for; open an issue and rename before the first non-plan commit.

Nothing here is pushed. The step 5 measurements are taken on top of `c75a333`, so they describe the
code that #79 left behind.
