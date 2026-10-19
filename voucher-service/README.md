# voucher-service

Internal campaign voucher redemption service.

Clients run promotional campaigns; each campaign has a fixed stock of vouchers.
End users redeem a voucher code via the storefront, which calls this service.

## Running

Two environment variables are required. The service refuses to start without
them rather than running with a blank credential — copy `.env.example` and fill
it in, or export them:

```
export AUDIT_API_KEY=...            # credential for the internal audit service
export JWT_SECRET=$(openssl rand -hex 32)   # HS256 signing secret, min 32 bytes
```

Then, with sample data loaded:

```
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Without the `dev` profile the schema is created empty. H2 in-memory by default;
the schema is owned by Flyway (`src/main/resources/db/migration`), and sample
data lives in `dev-seed.sql`, which the `dev` profile loads and the test suite
reuses.

## Authentication

All endpoints require a bearer JWT. The redeeming user is taken from the token's
`sub` claim — **not** from a request parameter, which is what previously made the
per-user limit advisory rather than enforced. Voiding additionally requires the
`voucher.admin` scope.

Tokens are verified with an HS256 shared secret (`JWT_SECRET`) so the service
runs without an identity provider to hand. **This is the piece to change before
production:** a symmetric secret can mint tokens as well as verify them. With a
real IdP, delete the `jwtDecoder` bean in `SecurityConfig` and set
`spring.security.oauth2.resourceserver.jwt.issuer-uri` instead; nothing else
needs to change.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/vouchers/{code}/redeem` | JWT | Redeems for the token's `sub` |
| POST | `/vouchers/{code}/void` | JWT + `voucher.admin` | Mark a voucher void |
| GET | `/campaigns/{id}/stats` | JWT | Stock + redemption counts |
| GET | `/campaigns/by-client/{clientCode}/stats` | JWT | Same, per client |

Unauthenticated requests get 401; a valid token without the admin scope gets 403
from `/void`. Business rejections remain HTTP 200 with a `reason` (below).

## Configuration

| Property | Env var | Default | Notes |
|---|---|---|---|
| `audit.api-key` | `AUDIT_API_KEY` | *none* | Sent as a bearer token. Startup fails if blank |
| `audit.url` | `AUDIT_URL` | internal audit host | |
| `audit.connect-timeout` | | `2s` | |
| `audit.read-timeout` | | `3s` | Without these a hung audit host exhausts the request thread pool |
| `app.security.jwt.secret` | `JWT_SECRET` | *none* | Min 32 bytes; startup fails if blank or short |

Both secrets are declared in `application.yml` with an **empty** default
(`${AUDIT_API_KEY:}`), not a bare `${AUDIT_API_KEY}`. Spring's binder leaves an
unresolvable placeholder as the literal string `"${AUDIT_API_KEY}"`, which is
non-blank — so validation would pass and the service would post that literal as
its credential. The empty default is what makes the fail-fast real.

## Schema management

Flyway owns the schema. Add a new `V2__*.sql` under `db/migration` rather than
editing `V1__init.sql`, which is checksummed once applied.

`spring.sql.init.mode` is `never` by default. It used to be `always` while
`schema.sql` began with `DROP TABLE IF EXISTS` — and `always`, unlike the
`embedded` default, applies to *any* datasource, so pointing the JDBC URL at a
real database would have destroyed it on startup.

## Per-user redemption limit

Each campaign carries a `max_per_user` cap (`INT NOT NULL DEFAULT 2`) on how
many vouchers one user may redeem from it. Exceeding it fails the redemption
with reason `USER_LIMIT_REACHED`.

**What counts toward the cap:** redemption log rows whose voucher is *currently*
`REDEEMED`. The `redemption` table is append-only, so a voucher that was
redeemed and later voided no longer counts — voiding returns the user's slot.

`redeemedCount` on the stats endpoints follows the same rule: it counts vouchers
whose status is currently `REDEEMED`, not vouchers that have a redemption log
row. A voucher voided after redemption therefore drops out of both the campaign
count and the user's allowance together.

## Voiding

| Voucher was | Effect | `remainingStock` in the response |
|---|---|---|
| `ACTIVE` | Code cancelled | `null` — it was never redeemed, so no unit was taken |
| `REDEEMED` | Redemption reversed | New value — the unit is returned, capped at `total_stock` |
| `VOID` | Rejected, `VOUCHER_ALREADY_VOID` | — |

Returning the unit matters in both directions: without it, stock drifts
permanently downward while the user's allowance comes back, so a campaign
eventually reports itself sold out while usable codes remain.

## Concurrency

Redemption takes no lock on the campaign. Both contended resources are claimed
by a conditional `UPDATE` whose `WHERE` clause *is* the guard, so the database
decides the winner in one statement:

```sql
UPDATE campaign SET remaining_stock = remaining_stock - 1
  WHERE id = ? AND remaining_stock > 0          -- 0 rows => out of stock

UPDATE voucher SET status = 'REDEEMED', ...
  WHERE id = ? AND status = 'ACTIVE'            -- 0 rows => someone else won
```

If the stock claim succeeds but the voucher claim then loses its race, the
transaction is marked rollback-only so the unit goes back rather than being
silently burned.

Only the per-user cap still needs serialising — it is a count compared against a
limit, which no single statement can guard. A write lock is taken on one row of
`user_campaign_allowance`, keyed by `(campaign_id, user_id)`. That narrows the
serialisation from *everyone on the campaign* to *one user on one campaign*: a
person double-clicking, rather than a queue of customers behind each other.

This replaced a pessimistic lock on the campaign row, which serialised every
redemption on a campaign — worst exactly during a flash sale.

`AllowanceGuard` uses H2's `MERGE ... KEY` to create the lock row on demand;
PostgreSQL needs `INSERT ... ON CONFLICT DO NOTHING` and MySQL `INSERT IGNORE`.
It is the one statement in the service that is not portable as written.

## Failure reasons

Failed redemptions return `result: "FAILED"` with a machine-readable `reason`
alongside the human-readable `message`:

| `reason` | Meaning |
|---|---|
| `VOUCHER_NOT_FOUND` | No voucher with that code |
| `VOUCHER_ALREADY_REDEEMED` | Voucher is already `REDEEMED`, or lost a race for it |
| `VOUCHER_VOID` | Cannot redeem: voucher is `VOID` |
| `VOUCHER_ALREADY_VOID` | Cannot void: voucher is already `VOID` |
| `CAMPAIGN_INACTIVE` | Campaign is not active |
| `USER_LIMIT_REACHED` | User is at the campaign's `max_per_user` cap |
| `CAMPAIGN_OUT_OF_STOCK` | No remaining stock |

Request-level failures use a separate body and a real status code: an unknown
campaign id returns **404** with `{"error":"CAMPAIGN_NOT_FOUND","message":...}`
rather than the 500 it used to produce. A *voucher* pointing at a missing
campaign is deliberately still a 500 — that is corrupt data, not a bad request.

Checks run in that order, so a user at their cap is told so even when the
campaign has also run out of stock.

On success `reason` serializes as `null` rather than being omitted — the DTO
sets no `@JsonInclude`, so this matches how `message` and `voucherCode` already
behave. `result` still carries `"OK"` / `"FAILED"`, so existing callers are
unaffected.

An unknown campaign id is still an unhandled `NoSuchElementException` (HTTP 500),
not a reason code.

## Sample data

| Campaign | Client | Stock | `max_per_user` | Vouchers | Why it exists |
|---|---|---|---|---|---|
| 1 | `ACME` | 100 | 2 | `RAYA-0001` … `RAYA-0006` | General fixture |
| 2 | `NOVA` | 50 | 2 | `MRDK-0001`, `MRDK-0002` | A second campaign, for cross-campaign checks |
| 3 | `ACME` | 0, inactive | 2 | `EXPD-0001` | Inactive path |
| 4 | `ZETA` | 18 | 2 | `LIMIT-0001` … `LIMIT-0018` | Limit tests, one disjoint slice each |
| 5 | `ZETA` | **0, active** | 2 | `SOLD-0001` … `SOLD-0004` | Out-of-stock path |
| 6 | `ZETA` | 8 | **1** | `ONE-0001` … `ONE-0006` | Limit other than 2 |
| 7 | `ZETA` | 6 | **3** | `TRIO-0001` … `TRIO-0004` | Limit other than 2 |
| 8 | `ZETA` | 4 | *(DB default)* | `DFLT-0001` … `DFLT-0003` | Exercises `DEFAULT 2` |
| 9 | `OMNI` | **1** | 2 | `OVER-0001` … `OVER-0006` | One unit behind six codes, for overselling |

Campaign 5 is the only campaign that reaches the out-of-stock check: stock ran
to zero while unredeemed codes remain. Campaign 3 is also empty but inactive, so
it is rejected one check earlier.

Campaigns 6 and 7 carry limits of 1 and 3 so that no test can pass by assuming
the number 2, and campaign 8 omits the column entirely so the DB default is
covered rather than taken on trust.

`user-99` already has redemption history on campaign 1: two log rows, but one of
those vouchers (`RAYA-0005`) was voided, so `user-99` counts as 1 of 2 used.
`user-sold-cap` is seeded at 2 of 2 on campaign 5, which is *also* out of stock —
that pair is what pins the check ordering above.

## Tests

```
./gradlew test
```

Each test method starts from freshly seeded data — the test classes carry
`@Sql({"/test-reset.sql", "/dev-seed.sql"})`, which clears all three tables and
reloads the sample data before every method. Execution order therefore cannot
matter. The schema itself is created once by Flyway, so the tests and the real
application share one definition of the DDL.

`VoucherApiSecurityTest` covers the HTTP layer: 401 without a token, 403 on
`/void` without the admin scope, and that a spoofed `userId` parameter cannot
raise a caller's allowance. `VoucherServiceTest` covers the redemption rules
directly against the service.

Test credentials come from `src/test/resources/application-test.yml` via
`@ActiveProfiles("test")`. It is profile-specific on purpose: a plain
`application.yml` in test resources would shadow the main one and take the
datasource and Flyway settings with it, rather than merging over them.

Re-seeding is used instead of wrapping each test in a rolled-back transaction
because a test-managed transaction would be invisible to the other threads in
the concurrency test, and an `AFTER_COMMIT` listener never fires for a
transaction that rolls back. Both would have been silently disabled.
