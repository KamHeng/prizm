## 1. What I changed, and why

### The feature

Each campaign carries `max_per_user` (`INT NOT NULL DEFAULT 2`, so existing
campaigns default to 2). Exceeding it rejects with `USER_LIMIT_REACHED` and a
message naming the cap.

**What counts toward the cap:** redemption log rows whose voucher is *currently*
`REDEEMED`. In the real world a voided voucher should not count against a user,
so voiding returns their slot. The seed data made this concrete — `user-99` has
two log rows on campaign 1 but one of those vouchers was voided, so they count
as 1 of 2 used, not 2 of 2.

### Fixes that followed

| Change | Why |
|---|---|
| **Redemption identity from a JWT subject.** `userId` was an unauthenticated query parameter; `/void` was wide open. Now all endpoints need a bearer token, the cap is counted against the token `sub`, and `/void` needs the `voucher.admin` scope. | Dangerous once this goes live — anyone could pass a fresh `userId` and drain a campaign, which made the limit decorative. |
| **Flyway replaces boot-time DDL.** `schema.sql` opened with `DROP TABLE IF EXISTS`, and `spring.sql.init.mode: always` applies to *any* datasource, not just embedded ones. Schema now lives in `db/migration`; sample data moved to `dev-seed.sql` behind the `dev` profile. | Needed for the long run: pointing the JDBC URL at a real database would have destroyed it on startup. |
| **Audit API key out of source.** Read from `AUDIT_API_KEY`, sent as a bearer header rather than a body field, and the app refuses to start without it. | Hardcoding a credential in source is simply not correct behaviour. |
| **Connect/read timeouts on the audit client** (2s / 3s). | Bundled with the above — a bare `RestTemplate` with no timeout lets a hung audit host exhaust the request thread pool. |
| **Void keeps stock honest.** Reversing a redemption returns the unit (capped at `total_stock`); voiding an `ACTIVE` code leaves stock alone; voiding an already-void voucher is rejected. | Stock accuracy matters in both directions — over-redeeming and under-redeeming are both wrong. Previously stock drifted down permanently while the user's allowance came back. |
| **404 for unknown campaign ids.** `{"error":"CAMPAIGN_NOT_FOUND","message":...}` instead of a 500 from `Optional.get()`. | A clearer error when a wrong campaign id is passed, which makes maintenance and bug-hunting easier. |
| **Lock-free claims.** Stock and the voucher are each claimed by a conditional `UPDATE` whose `WHERE` clause is the guard. Only the per-user cap still serialises, narrowed to one `(campaign, user)` row. | User experience: the previous campaign-wide lock serialised *every* redemption on a campaign, worst exactly during a flash sale. Flagged as needing real testing, which it got. |
| **Stats N+1 removed.** One grouped `COUNT(*) … GROUP BY campaign_id, status` instead of a query per campaign plus a lookup per voucher. | Low-risk fix with a clear benefit — an easy yes. |
| **`redeemedCount` counts current status**, not log rows. | So it agrees with the per-user limit, which counts the same way. The two had silently diverged. |
| **Machine-readable `reason`** on every failure path. | A client cannot reliably react to free-text like "limit reached"; it needs a stable code such as `USER_LIMIT_REACHED` to pick the right UI. |
| **`@TransactionalEventListener(AFTER_COMMIT)`** for the audit call. | Keeps the audit write in sync with the real outcome: it only fires if the redemption commits, so a rolled-back redemption never leaves a false audit row. It also avoids splitting methods into transactional / non-transactional pairs. |
| **Test isolation and coverage.** Every test starts from a rebuilt database, method order is randomised, and fixtures now include a sold-out campaign, caps of 1 and 3, and a campaign with the column omitted. | Without caps other than 2, a hardcoded `>= 2` would have passed the whole suite. |

---

## 2. Noticed, deliberately not changed

**Audit events are lost silently.** If the audit service is down the event is
logged and dropped — the trail is incomplete with nothing recording that it is
incomplete. This is a robustness issue in the audit path, not part of the
per-user limit, so it could wait. The real fix is a transactional outbox or a
durable queue, not a bigger catch block.

**Locking is only verified on H2.** The concurrency guarantees rest on
`SELECT … FOR UPDATE` and conditional-update semantics that differ across
engines. Acceptable for now because this is only being exercised on H2 and is
not going live.

**`AllowanceGuard` uses H2's `MERGE … KEY`** to create the lock row on demand.
PostgreSQL needs `INSERT … ON CONFLICT DO NOTHING`, MySQL `INSERT IGNORE`. It is
the one non-portable statement in the service, documented in the class and the
README, and the first line to revisit when moving off H2.

**Failures still return HTTP 200.** Changing them would break the contract the
storefront already depends on. Reason codes were added instead, which gives
clients what they need without a migration. The cost is that error rates stay
invisible to monitoring — see the next section.

**`RedemptionRepository.findByVoucherId` is now dead code**, with zero callers
after the stats rewrite. Left in place rather than removing a public repository
method unilaterally.

**Randomised test order has no pinned seed.** Good for catching order
dependence, but a future failure of that kind will not reproduce. One line in
`junit-platform.properties` fixes it when it becomes a problem.

---

## 3. With another day

These are the items left untouched for time. Roughly in the order I would take
them.

**Status as a typed enum.** `Voucher.status` is a free-form `String` compared
against `"ACTIVE"` / `"REDEEMED"` / `"VOID"` literals in six places. A typo like
`"redeemed"` compiles cleanly and silently never matches — the voucher becomes
permanently unredeemable with no error anywhere. This is the highest-value
cleanup because the failure mode is invisible rather than loud.

**Testcontainers against the real engine.** This is what would make the
concurrency work trustworthy rather than merely green. Every guard is verified
only against H2, and the one non-portable statement (`MERGE … KEY`) sits in the
hot path. A passing suite currently proves correctness on a database nobody will
deploy on.

**Audit durability — a transactional outbox.** Write the event in the same
transaction as the redemption and drain it with a poller. If audit exists for
compliance, "we logged the failure" is not a recovery mechanism.

**Foreign keys and `UNIQUE(voucher_id)` on `redemption`.** There are no foreign
keys and nothing stops two log rows for one voucher. The limit *count* is
defended with `COUNT(DISTINCT voucher_id)`, but the data itself is not — a
duplicate row is still wrong, just no longer harmful.

**Idempotency.** A client that times out and retries gets
`VOUCHER_ALREADY_REDEEMED` even when *that same user* succeeded, so someone who
did get their voucher is shown an error. Returning `OK` when
`redeemedBy.equals(userId)`, or an idempotency key stored with the redemption,
fixes it.

**Metrics per `FailureReason`.** Since failures are HTTP 200 by design, nothing
downstream can see an error rate. Counters per reason restore the signal without
touching the contract.

**Smaller items:** `java.util.Date` → `Instant`; `@NotBlank` on `userId` (today
`?userId=` creates its own limit bucket, and `spring-boot-starter-validation` is
already on the classpath, unused); `gradlew.bat` is committed as
**`gradlew.bat.txt`**, so the documented Windows entry point does not work; and
a `.gitattributes` to stop CRLF churn.

---

## Reflections

### What did you get wrong first, and how did you notice?

I thought the feature was finished when ten tests passed. It wasn't. Every
campaign in the seed data had `max_per_user = 2`, so a hardcoded `>= 2` would
have passed the entire suite — the tests proved the number 2 worked, not that
the limit was read from the campaign. Two other holes sat next to it: nothing
checked the count was scoped per campaign, and nothing exercised the column's
`DEFAULT 2`, because the seed set it explicitly on every row.

That changed how I verified everything afterwards: each guard was deliberately
removed to confirm a test caught it — nine mutations in total, each failing
exactly the test written for it. It paid off most sharply on the fail-fast
config. My first attempt declared `api-key: ${AUDIT_API_KEY}` with no default
and I assumed a missing variable would refuse startup. It doesn't. Spring's
binder leaves an unresolvable placeholder as the literal string
`"${AUDIT_API_KEY}"`, which is non-blank, so `@NotBlank` passed and the service
would have posted that literal as its credential. I only caught it because a
`BUILD FAILED in 3m` turned out to be my own timeout killing a *running* app.

### Which AI suggestion did you reject, and why?

- **Counting redemption log rows.** Recommended as simpler, and consistent with
  void not restoring stock either. Rejected: in the real world a voided voucher
  should not count against a user, so the slot and the stock both come back.
- **Matching the existing unsafe style for concurrency**, leaving the race as a
  follow-up. Rejected: a per-user limit is a check-then-act rule, and without a
  lock two simultaneous requests can both pass the check and exceed the limit.
  Shipping a cap that does not hold is not shipping the feature.
- **Keeping `fail(message)` and not touching the other failure paths.**
  Rejected: a client cannot reliably react to free-text messages like "limit
  reached". It needs a stable code such as `USER_LIMIT_REACHED` to show the
  right UI.
- **Splitting the service into transactional / non-transactional method pairs**
  to move the audit call after commit. Rejected in favour of an `AFTER_COMMIT`
  event listener, which keeps the audit write in sync with the real outcome — it
  only fires if the redemption commits, so a rolled-back redemption never leaves
  a false audit row — and avoids splitting every method that needs this.
- **Skipping the concurrency test** as slow and the least stable in the suite.
  Rejected: the race is the riskiest part of the feature, and a feature whose
  main risk is concurrency needs a test that exercises it. It went on to catch
  three bugs no other test found.
- **Opening with a list of the repo's problems.** Deferred rather than rejected:
  I wanted the feature built before getting buried in a long list of issues.
  A prioritisation choice — the issues were worked through afterwards.

### What took you longest?

Getting the voucher-redemption race right. It went through three designs.

The original `redeem()` was check-then-act on both voucher status and stock, with
no transaction at all. Adding `@Transactional` and a lock on the campaign row
looked sufficient and wasn't: the voucher-status checks ran *before* the campaign
was loaded, so two requests for the same code could both read `ACTIVE`,
serialise on the campaign lock, and both still write `REDEEMED`. Locking the
voucher and then the campaign, always in that order, fixed correctness — but it
serialised every redemption on a campaign, so one slow request queued every
other customer behind it.

The version that stands replaces both locks with conditional updates whose
`WHERE` clause *is* the guard — `remaining_stock > 0`, `status = 'ACTIVE'` — so
the database picks the winner in a single statement and holds no lock. Only the
per-user cap still needs serialising, because a count compared against a limit
cannot be expressed that way, and that is narrowed to one `(campaign, user)`
row.

The subtle part came last. If the stock claim succeeds and the voucher claim
*then* loses its race, the unit has already left the campaign. Without marking
the transaction rollback-only, six racers for one code would burn six units of
stock and hand out one voucher. The test asserts stock lands on 17, not 12.

What actually took the time was not writing any of these — it was establishing
that each one worked. A green concurrency test means very little on its own, so
every guard was removed in turn to confirm the test failed without it. All four
did, each failing exactly the test written for it. That is the only reason I
trust the final design more than the two I discarded.
