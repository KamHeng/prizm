# voucher-service — working session record

A record of the Claude Code session that added a per-user redemption limit to
`voucher-service` and then hardened the service around it.

**What this document is.** A structured reconstruction, not a raw transcript.
User requests are quoted verbatim (they were short). Assistant responses are
condensed to their substance — decisions, reasoning and outcomes. Command output
and test results are quoted as they appeared. Nothing here is a reconstruction
from memory: it is drawn from the session itself.

**Date:** 2026-10-19
**Repo:** `voucher-service-takehome/voucher-service`
**Outcome:** per-user redemption limit delivered, plus five further fixes;
test count 3 → 40, all passing.

---

## Table of contents

1. [Starting point](#1-starting-point)
2. [Designing the limit](#2-designing-the-limit)
3. [Implementation, and a toolchain wall](#3-implementation-and-a-toolchain-wall)
4. [Getting it to run](#4-getting-it-to-run)
5. [Closing the real test gaps](#5-closing-the-real-test-gaps)
6. [Test isolation and live HTTP](#6-test-isolation-and-live-http)
7. [Making stats agree with the limit](#7-making-stats-agree-with-the-limit)
8. [The remaining-issues review](#8-the-remaining-issues-review)
9. [Security and data safety](#9-security-and-data-safety)
10. [Stock integrity, 404s, concurrency, N+1](#10-stock-integrity-404s-concurrency-n1)
11. [Consolidated decision log](#11-consolidated-decision-log)
12. [Verification log](#12-verification-log)
13. [Mistakes made and caught](#13-mistakes-made-and-caught)
14. [Final state](#14-final-state)
15. [Still open](#15-still-open)

---

## 1. Starting point

> **User:** Read the code and explain to me the system briefly. No need to read BRIEF.md

A small Spring Boot 3.2.5 / Java 17 service (`com.prizm.campaign`, v1.4.2) for
redeeming promotional campaign vouchers. H2 in-memory, schema and seed loaded
from SQL at startup (`ddl-auto: none`), Gradle.

**Domain** — three tables, no JPA relations anywhere (raw ids throughout):

| Entity | Fields |
|---|---|
| `Campaign` | name, `clientCode`, `totalStock`, `remainingStock`, `active` |
| `Voucher` | `campaignId`, `code`, status string `ACTIVE`/`REDEEMED`/`VOID`, `redeemedBy`, `redeemedAt` |
| `Redemption` | append-only row per redemption |

**Two flows** — redeem (`POST /vouchers/{code}/redeem?userId=`) and stats
(`GET /campaigns/{id}/stats`, `/campaigns/by-client/{clientCode}/stats`).
Failures came back as HTTP 200 with `result: "FAILED"`.

Problems visible on first read, reported at the time: no transaction or locking
on `redeem()` (check-then-act on both voucher status and stock); a synchronous
audit HTTP call with no timeout inside the flow; a hardcoded API key; unchecked
`Optional.get()`; N+1 queries in stats; `java.util.Date`; status as a free-form
string; `voidVoucher` ignoring state; three shallow order-dependent tests.

---

## 2. Designing the limit

> **User:** let's not discuss about the problems first. I want to integrate a per-user redemption limit, each campaign should define the maximum number of vouchers a user may redeem for that campaign. when a user try to redeem more than the limit it should reject with clear reason. the default limit will be 2 for the existing campaign.
>
> Write me plan on your suggestion first before implement anything. Feel free to point out the issue and ask for confirmation if you are not sure with anything. Do not assume anything all decision should be made by me

### The seed-data trap

`user-99` had **two** rows in `redemption` for campaign 1 — voucher 4 (still
`REDEEMED`) and voucher 5 (since `VOID`). So "count the user's redemptions" was
ambiguous in a way that showed up immediately: with a limit of 2, `user-99` was
either already blocked (2/2) or had one slot left (1/2) depending purely on how
counting was defined. That choice also silently decided whether voiding a
voucher gives a user their slot back.

### Decisions, round 1

| Question | Options offered | Chosen |
|---|---|---|
| What counts toward the cap | log rows / only still-`REDEEMED` vouchers / voucher table | **only still-`REDEEMED`** (void frees a slot; `user-99` = 1/2) |
| Column definition | `NOT NULL DEFAULT 2` / nullable = unlimited / `NOT NULL` no default | **`INT NOT NULL DEFAULT 2`** |
| Concurrency | match existing (unsafe) / `@Transactional` + row lock / `@Transactional` only | **`@Transactional` + row lock** |
| Error shape | existing `fail(message)` / machine-readable code / HTTP status codes | **machine-readable `reason`** |

Choosing `@Transactional` surfaced three consequences, raised before writing
code:

1. The audit HTTP call would end up *inside* the transaction, holding the
   campaign row lock across a network call with no timeout.
2. Locking only the campaign row is not enough — the voucher-status checks ran
   before the campaign was loaded, so two requests for one code could both read
   `ACTIVE`, serialize on the campaign lock, and both write `REDEEMED`.
3. `voidVoucher` would gain a new asymmetry: voiding a redeemed voucher returns
   the user's slot but not the campaign's stock.

### Decisions, round 2

| Question | Chosen | Why it mattered |
|---|---|---|
| Audit call placement | **`@TransactionalEventListener(AFTER_COMMIT)`** | Locks released before the network call |
| Lock scope | **voucher then campaign** | Fixed order, single write path, no deadlock; closes the same-code race |
| Check order | **after active, before stock** | A user at their cap hears that, not "out of stock" |
| Test fixture | **new seed campaign** | Campaign 1 had only four `ACTIVE` codes left |

Deliberately out of scope at this point: `voidVoucher`, the `.get()` 500s, the
hardcoded key, the stats N+1, the `RestTemplate` timeout.

---

## 3. Implementation, and a toolchain wall

> **User:** Implement the plan as written, including the unique index on voucher.code and the optional concurrency test.

Implemented across 14 files: `max_per_user` column, `Campaign.maxPerUser`,
`countActiveRedemptionsByUser` (a `count(distinct …)` join, since `Redemption`
has no JPA relation to `Voucher`), `findByCodeForUpdate` /
`findByIdForUpdate`, a `FailureReason` enum, `RedemptionRecorded` +
`RedemptionAuditListener`, and 10 tests over a dedicated campaign 4 with 18
vouchers in disjoint per-test slices.

**Then nothing would build.** No JDK on the machine; the only JVM was
PhpStorm's bundled JetBrains Runtime (JDK 25), and the project's Gradle 8.7
supports Java 8–22:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69
```

Also found: `gradlew.bat` is committed as **`gradlew.bat.txt`**, and `gradlew`
itself is a 7-line stub that execs `java` off `PATH` rather than honouring
`JAVA_HOME`.

Reported plainly that every claim was a static-review claim, not a test result,
and asked how to proceed rather than guessing.

---

## 4. Getting it to run

> **User:** Install Temurin 21 via winget so the build can run, then run the test suite.

Installed Temurin 21.0.12. **10 tests, 0 failures.**

### The teeth check

A passing concurrency test proves nothing on its own, so the locking was removed
on purpose:

| Mutation | Result |
|---|---|
| Both `ForUpdate` locks → plain reads | `concurrentRedemptionsRespectUserLimit` **FAILED** |

Reverted, re-ran green. The lock was doing real work.

### A documentation claim corrected

The README had said `reason` is *absent* on success. It isn't —
`RedeemResponse` sets no `@JsonInclude`, so Jackson emits `"reason": null`,
exactly as `message` and `voucherCode` already did. Corrected.

---

## 5. Closing the real test gaps

> **User:** 1. create a new test data that has out of stock. we will need to make sure this is tested
>
> problem in the repo put aside first we need to make sure the implementation of the limit per user have done and covered all testing

Reviewing the existing tests turned up three holes larger than the one asked
about:

1. **Every campaign had `max_per_user = 2`** — a hardcoded `>= 2` would have
   passed all 10 tests.
2. **Nothing scoped the count per campaign** — dropping the `campaignId`
   predicate from the count query was undetectable.
3. **`DEFAULT 2` was never exercised** — the seed set the column explicitly on
   every row.

New fixtures:

| Campaign | Stock | `max_per_user` | Purpose |
|---|---|---|---|
| 5 `SOLD-*` | **0, active** | 2 | The only campaign reaching the out-of-stock check (3 is empty but inactive, so it short-circuits earlier) |
| 6 `ONE-*` | 8 | **1** | A cap that is not 2 |
| 7 `TRIO-*` | 6 | **3** | A cap that is not 2 |
| 8 `DFLT-*` | 4 | *(DB default)* | Column omitted, so `DEFAULT 2` is tested rather than trusted |

`user-sold-cap` was seeded at 2/2 on campaign 5 — a campaign that is *also* out
of stock — so `userLimitIsReportedBeforeOutOfStock` pins the agreed check order.

**18 tests.** Mutation results:

| Mutation | Tests caught it |
|---|---|
| `getMaxPerUser()` → `2` | **4 failed** (would have been 0 before) |
| Stock checked before limit | **1 failed** |

---

## 6. Test isolation and live HTTP

> **User:** fix the rollback issue first then we can run the project at local to test on real http api call to make sure everything works well

### Why re-seeding, not rollback

Wrapping each test in a rolled-back transaction would have silently disabled two
things: other threads cannot see an uncommitted test transaction (killing the
concurrency test), and `@TransactionalEventListener(AFTER_COMMIT)` never fires
for a transaction that rolls back (killing the audit path). So the class got
`@Sql({"/schema.sql", "/data.sql"})` — a full rebuild before each method, with
every redemption still a real commit.

### An overstatement corrected

The earlier claim that the suite was "order-dependent by construction" was
wrong. With randomized ordering across four runs it passed *without* `@Sql` at
all — the disjoint-slice bookkeeping had been airtight. The risk was latent, not
live, and saying otherwise had been sloppy.

So `@Sql` was proved rather than assumed, using a probe test that reuses a code
another test consumes — the mistake the next person to add a test would make:

| | Without `@Sql` | With `@Sql` |
|---|---|---|
| Existing 18 tests, randomized ×4 | pass | pass |
| \+ probe reusing `RAYA-0001` | **fail 3/3** | **pass 3/3** |

`src/test/resources/junit-platform.properties` was added to randomize method
order so the suite self-polices independence. Flagged as a trade-off (losing
reproducibility unless the seed is pinned).

### Live HTTP

Booted on `localhost:8080`; 16 calls. Caps enforced at 2 and 1 with the message
reading `(1 of 1 used)`; `SOLD-0001` as a fresh user → `CAMPAIGN_OUT_OF_STOCK`
while `SOLD-0002` as a capped user → `USER_LIMIT_REACHED` on the same sold-out
campaign; void-then-retry → `OK`; all reason codes correct.

**The audit listener fired exactly 4 times — once per `OK`, zero for the 7
rejections**, with the DNS failure swallowed and 0 dispatcher errors. The
`AFTER_COMMIT` wiring verified against the real client, not just a mock.

---

## 7. Making stats agree with the limit

The HTTP run exposed a divergence: after voiding a redeemed voucher, campaign 4
reported `redeemedCount: 3` while only 2 vouchers were actually `REDEEMED`.
Stats counted any voucher with a log row; the limit deliberately excluded voided
ones.

> **User:** Yes — make CampaignStats.redeemedCount count only vouchers currently REDEEMED, so it agrees with the per-user limit count. Add a test for it.

Two tests, chosen to fail for different reasons: one pins the rule against the
seed (campaign 1 has two log rows but one voided → answer is 1, not 2), the
other pins the *agreement* (one void, then asserts both the count dropping and
the user's third redemption succeeding).

Verified live: `/campaigns/1/stats` `redeemedCount` 2 → **1**.
Mutation: restoring log-row counting failed **exactly those two** tests.

Side effect worth noting — removing the per-voucher redemption lookup
eliminated one of the two N+1 patterns, and `CampaignStatsService` stopped
injecting `RedemptionRepository` entirely.

**20 tests.**

---

## 8. The remaining-issues review

> **User:** now list out the remaining issue and what is the concern if the issue does not solve and stay in the project. then list out the possible way of fixes

Twenty issues in four tiers, each with its risk and fix options, with the six
introduced by this session's own work marked as such. The headline findings:

**Tier 1 — dangerous**

1. **The per-user limit was trivially bypassable.** `userId` was an
   unauthenticated query parameter with no security config anywhere. Anyone
   could send `?userId=whatever` and drain a campaign; `/void` was equally open.
   The feature stopped honest double-clicks, not abuse.
2. **Startup dropped every table.** `schema.sql` began with `DROP TABLE IF
   EXISTS` and `spring.sql.init.mode: always` applies to *any* datasource,
   unlike the `embedded` default — so repointing the JDBC URL at a real database
   would destroy it on boot.
3. **Audit API key hardcoded**, sent in the request body.

**Tier 2** — no HTTP timeouts; audit events lost silently; `voidVoucher`
unguarded; `Optional.get()` → 500; locking verified only on H2.

**Tier 3** — the campaign row lock serializing every redemption on a campaign;
`getStatsForClient` N+1 and unbounded in memory.

**Tier 4** — status as `String`; `java.util.Date`; mixed DI; no FKs; unvalidated
`userId`; HTTP 200 for failures; no idempotency; the `gradlew.bat.txt` wrapper;
random order without a pinned seed; no HTTP-layer tests.

---

## 9. Security and data safety

> **User:** 1. this need to be fix as this is dangerous when this entire project goes live
> 2. This also need to fix for long run
> 3. move the API to env file instead of hardcoded in source which is not a correct behavior

| Question | Chosen |
|---|---|
| Identity | **JWT resource server** — `sub` replaces the parameter; `/void` needs `voucher.admin` |
| Schema management | **Flyway** — `V1__init.sql`, `sql.init.mode: never` |
| Secret | **Env var, fail fast** — bearer header, `.env.example`, `.env` gitignored |
| Timeouts | **Included** — 2s connect / 3s read |

### The bug in my own "fail fast"

`api-key: ${AUDIT_API_KEY}` with no default was expected to refuse startup. It
didn't. **Spring's binder leaves an unresolvable placeholder as literal text**,
so `audit.api-key` became the string `"${AUDIT_API_KEY}"` — non-blank, so
`@NotBlank` passed and the service would have posted that literal as its bearer
token. It only came to light because `BUILD FAILED in 3m` turned out to be a
`timeout 180` killing a *running* app.

Fix: an explicit empty default (`${AUDIT_API_KEY:}`) so validation has something
to reject. Four paths then verified:

| Condition | Result |
|---|---|
| Neither secret set | Refuses — `AuditProperties` binding |
| `AUDIT_API_KEY` missing | Refuses in 6s — `Property: audit.apiKey / Reason: must not be blank` |
| `JWT_SECRET` missing | Refuses — names the variable, gives the `openssl` command |
| `JWT_SECRET` 16 bytes | Refuses — `must be at least 32 bytes for HS256, got 16` |

### Proving the data-safety fix

A file-backed H2 database, booted twice. The first attempt *looked* like a
failure, so the database file was read directly instead of trusting the app:

| Evidence | Result |
|---|---|
| DB file after boot 1 | `LIMIT-0001 \| REDEEMED \| persist-user`, `remaining_stock = 17`, 5 redemption rows |
| Boot 2, no `dev` profile | `remainingStock: 17, redeemedCount: 1` — survived |
| Flyway on boot 2 | `Successfully validated 1 migration`, `Current version: 1` |

The first run's apparent data loss was H2 not flushing before a hard `kill`,
confirmed by the direct file read. Under the old config, boot 2 would have come
back empty.

### A caveat on the auth work

The intended mutation test — reverting the controller to the query parameter —
was **refused by the sandbox** as a deliberate security weakening. The secure
version was restored immediately and not retried. So this fix has weaker
evidence than the others: the controller has no `userId` parameter at all, so no
code path can read it, and `spoofedUserIdParameterCannotRaiseTheLimit` sends
three different `userId` values with the same token and still hits the cap on
the third — but no red-to-green transition was demonstrated.

`VoucherApiSecurityTest` (8 tests) also closed the "no HTTP-layer tests" gap.
**28 tests.**

---

## 10. Stock integrity, 404s, concurrency, N+1

> **User:** 5. put this asside first.
> 6. this need to fix as the stock of the voucher is important for over redeem or under redeem
> 7. Yes add this so when connect to the api it have a clearer error when passing wrong campaign id easier for maintenance or bug check as well
> 8. this is fine for now as this will be test on H2 only without really going to live
> 9. this need to be fix in terms or user experience but this require testing to make sure everything works well
> 10. yes fix the N+1 issue

| Question | Chosen |
|---|---|
| Replace the campaign lock | **Atomic SQL + per-user lock** — alternatives were a unique slot constraint (would have reversed the "void frees a slot" decision), SERIALIZABLE + retry, or atomic stock only |
| Void semantics | **One endpoint, restore on reversal** |

### Concurrency (item 9)

Both contended resources are now claimed by a conditional `UPDATE` whose `WHERE`
clause *is* the guard, so the database picks the winner in one statement and no
lock is held:

```sql
UPDATE campaign SET remaining_stock = remaining_stock - 1
  WHERE id = ? AND remaining_stock > 0          -- 0 rows => out of stock

UPDATE voucher SET status = 'REDEEMED', ...
  WHERE id = ? AND status = 'ACTIVE'            -- 0 rows => someone else won
```

If the stock claim succeeds and the voucher claim *then* loses its race, the
transaction is marked rollback-only so the unit goes back instead of being
silently burned.

Only the per-user cap still serializes — a count against a limit cannot be
guarded by one statement — narrowed to one row of `user_campaign_allowance`
keyed by `(campaign_id, user_id)`. That reduces serialization from *everyone on
the campaign* to *one user on one campaign*.

### Void (item 6)

| Was | Effect | Response `remainingStock` |
|---|---|---|
| `ACTIVE` | Cancelled; no unit was ever taken | `null` |
| `REDEEMED` | Reversal; unit returned, capped at `total_stock` | new value |
| `VOID` | Rejected, new `VOUCHER_ALREADY_VOID` | — |

### 404 (item 7)

`GET /campaigns/99999/stats` → `404 {"error":"CAMPAIGN_NOT_FOUND","message":"No
campaign with id 99999"}`. A *voucher* referencing a missing campaign stays a
500 with an explicit message — corrupt data, not a bad request.

### N+1 (item 10)

`getStatsForClient` is two queries flat: one for the campaigns, one grouped
`COUNT(*) ... GROUP BY campaign_id, status`. No voucher entities are loaded.

Asserted by measurement via Hibernate statistics: **2 queries for a 5-campaign
client, equal to the count for a 1-campaign client** (previously 11 vs 3). The
one test here that needed no mutation to be meaningful, because it measures the
quantity directly.

**40 tests.**

---

## 11. Consolidated decision log

Every design decision was put to the user rather than assumed.

| # | Decision | Chosen |
|---|---|---|
| 1 | What counts toward the cap | Only still-`REDEEMED` vouchers; voiding frees a slot |
| 2 | Column definition | `max_per_user INT NOT NULL DEFAULT 2` |
| 3 | Concurrency approach (initial) | `@Transactional` + pessimistic row locks |
| 4 | Error reporting | Machine-readable `reason` on `RedeemResponse` |
| 5 | Audit call placement | `@TransactionalEventListener(AFTER_COMMIT)` |
| 6 | Lock scope | Voucher then campaign, one fixed order |
| 7 | Check order | Limit before stock |
| 8 | Test fixtures | Dedicated seed campaigns |
| 9 | Unique index on `voucher.code` | Yes |
| 10 | Concurrency test | Yes |
| 11 | Toolchain | Install Temurin 21 |
| 12 | `redeemedCount` semantics | Count only currently `REDEEMED` |
| 13 | Identity | JWT resource server; `/void` needs `voucher.admin` |
| 14 | Schema management | Flyway |
| 15 | Secret handling | Env var, fail fast, bearer header |
| 16 | HTTP timeouts | Included |
| 17 | Replace the campaign lock | Atomic SQL + per-user allowance lock |
| 18 | Void semantics | One endpoint, restore stock on reversal |

Explicitly deferred by the user: audit event durability (Tier 2 item 5) and
non-H2 lock verification (item 8).

---

## 12. Verification log

Green tests were never treated as proof on their own. Each guard was removed
deliberately and reverted:

| Mutation | Caught by | Session |
|---|---|---|
| Both `ForUpdate` locks → plain reads | `concurrentRedemptionsRespectUserLimit` | §4 |
| `getMaxPerUser()` → `2` | 4 tests | §5 |
| Stock checked before limit | `userLimitIsReportedBeforeOutOfStock` | §5 |
| Remove `@Sql`, add a code-reusing probe | probe failed 3/3 | §6 |
| Restore log-row `redeemedCount` | the 2 new stats tests | §7 |
| Drop `remaining_stock > 0` | `concurrentRedemptionsCannotOversellStock`, `redemptionOnOutOfStockCampaignIsRejected` | §10 |
| Drop `status = 'ACTIVE'` | `concurrentRedemptionsOfOneCodeYieldExactlyOneWinner` | §10 |
| Drop `setRollbackOnly` | `concurrentRedemptionsOfOneCodeYieldExactlyOneWinner` | §10 |
| Drop the per-user allowance lock | `concurrentRedemptionsRespectUserLimit` | §10 |

Every mutation failed exactly the test written for it. After the batched run the
guards were re-checked in the source rather than trusted, because the script's
`EXIT` trap fired after its backups had been deleted and the final restore
silently no-op'd.

**Not covered by mutation:** the auth fix (sandbox refused — see §9) and the
N+1 fix (measured directly instead).

Beyond the suite, behaviour was confirmed against a running service over HTTP in
three separate sessions, including the audit listener firing exactly once per
commit and zero times per rejection.

---

## 13. Mistakes made and caught

Recorded because they are the useful part of the record.

1. **A "fail fast" that didn't fail** (§9). Spring leaves unresolvable
   placeholders as literal text, so a missing secret produced the string
   `"${AUDIT_API_KEY}"` and passed `@NotBlank`. Found only by actually booting
   without the variable.
2. **An overstated problem** (§6). The suite was claimed "order-dependent by
   construction"; randomized runs showed it was already order-independent. The
   fix was still worth making, but the justification had to be corrected.
3. **A wrong documentation claim** (§4). `reason` was described as absent on
   success; Jackson emits it as `null`.
4. **An inconclusive experiment reported as inconclusive** (§9). The first
   two-boot persistence check appeared to fail; rather than explaining it away,
   the DB file was read directly, which showed the cause was H2's flush timing.
5. **A self-inflicted divergence** (§7). Choosing "count only still-redeemed"
   for the limit left `redeemedCount` disagreeing with it — surfaced by the live
   HTTP run, not by the tests.

---

## 14. Final state

### `redeem()` flow

```
@Transactional
redeem(code, userId):
  voucher = findByCode(code)                     plain read
    null / REDEEMED / VOID    -> fail(reason)
  campaign = findById(voucher.campaignId)
    missing -> IllegalStateException             corrupt data, 500
    !active -> fail(CAMPAIGN_INACTIVE)

  allowanceGuard.lockFor(campaignId, userId)     locks ONE (campaign,user) row
  used = countActiveRedemptionsByUser(...)
    used >= maxPerUser -> fail(USER_LIMIT_REACHED, "N of M used")

  claimStock(campaignId)                         UPDATE ... WHERE remaining > 0
    0 rows -> fail(CAMPAIGN_OUT_OF_STOCK)
  claimForRedemption(voucherId, userId, now)     UPDATE ... WHERE status='ACTIVE'
    0 rows -> setRollbackOnly(); fail(VOUCHER_ALREADY_REDEEMED)

  save(Redemption); publishEvent(RedemptionRecorded)
  return ok(code, findRemainingStock(campaignId))
```

### Files

**Added (13):** `config/SecurityConfig`, `controller/ApiExceptionHandler`,
`dto/ApiError`, `dto/FailureReason`, `repository/AllowanceGuard`,
`service/AuditProperties`, `service/CampaignNotFoundException`,
`service/RedemptionRecorded`, `service/RedemptionAuditListener`,
`db/migration/V1__init.sql`, `db/migration/V2__user_campaign_allowance.sql`,
`dev-seed.sql`, `application-dev.yml`, `.env.example`, plus test resources
(`application-test.yml`, `test-reset.sql`, `junit-platform.properties`) and
`VoucherApiSecurityTest`.

**Removed (2):** `schema.sql`, `data.sql` — superseded by Flyway and
`dev-seed.sql`.

**Modified:** `build.gradle`, `CampaignApplication`, `VoucherController`,
`AuditClient`, `VoucherService`, `CampaignStatsService`, all three
repositories, `Campaign`, `CampaignStats`, `RedeemResponse`, `application.yml`,
`.gitignore`, `README.md`, `VoucherServiceTest`.

### Tests: 3 → 40

| Stage | Count |
|---|---|
| Original repo | 3 |
| Limit implemented | 10 |
| Coverage gaps closed | 18 |
| Stats semantics | 20 |
| Security + HTTP layer | 28 |
| Stock, 404, concurrency, N+1 | **40** |

`VoucherServiceTest` 30 · `VoucherApiSecurityTest` 10 · 0 failures.
Each method starts from a rebuilt database; execution order is randomized.

### Running it

```bash
export AUDIT_API_KEY=...
export JWT_SECRET=$(openssl rand -hex 32)
./gradlew bootRun --args='--spring.profiles.active=dev'
```

On a machine without a JDK on `PATH` and with `gradlew.bat` misnamed:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
java -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain test --no-daemon --console=plain
```

### Breaking changes

`POST /vouchers/{code}/redeem?userId=…` no longer exists in that form — callers
send `Authorization: Bearer <jwt>` and the parameter is ignored. All endpoints
401 without a token, so the storefront must change in step. `bootRun` now
requires both environment variables.

---

## 15. Still open

**Deferred by the user**

- Audit events are lost silently on failure; the trail is incomplete with
  nothing recording that it is incomplete. Fix: transactional outbox, durable
  queue, or retry plus a dead-letter row and an alert.
- Locking is verified only on H2, whose `SELECT … FOR UPDATE` semantics differ
  from Postgres and MySQL. Fix: Testcontainers against the real engine.

**Known edge introduced by the concurrency work**

- `AllowanceGuard` uses H2's `MERGE … KEY`. PostgreSQL needs `INSERT … ON
  CONFLICT DO NOTHING`, MySQL `INSERT IGNORE`. The one non-portable statement in
  the service, and the first line to revisit when moving off H2 — which is also
  exactly what H2 cannot validate.

**Tier 4 cleanup, untouched**

| Issue | Risk |
|---|---|
| `status` is a `String`, compared against literals | A typo compiles and never matches, making a voucher permanently unredeemable with no error |
| `java.util.Date` on `Voucher`/`Redemption` | Mutable, no timezone, legacy API |
| No FKs; no `UNIQUE(voucher_id)` on `redemption` | Orphan and duplicate rows (the *count* is defended with `distinct`; the data is not) |
| `userId` unvalidated | `?userId=` becomes its own limit bucket |
| Failures return HTTP 200 *(deliberate)* | No error rate for monitoring; add per-reason metrics rather than changing codes |
| No idempotency | A client retry after a timeout sees `VOUCHER_ALREADY_REDEEMED` though that same user succeeded |
| `gradlew.bat` committed as `gradlew.bat.txt` | Windows contributors cannot build |
| Random test order, no pinned seed | A future order-dependent failure will not reproduce |

Two Tier-4 items were closed incidentally: `VoucherService` and
`VoucherController` moved to constructor injection, and HTTP-layer tests now
exist.
