# voucher-service

Internal campaign voucher redemption service.

Clients run promotional campaigns; each campaign has a fixed stock of vouchers.
End users redeem a voucher code via the storefront, which calls this service.

## Running

```
./gradlew bootRun
```

H2 in-memory DB, seeded from `src/main/resources/schema.sql` + `data.sql` on startup.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/vouchers/{code}/redeem?userId=...` | Redeem a voucher |
| POST | `/vouchers/{code}/void` | Mark a voucher void |
| GET | `/campaigns/{id}/stats` | Stock + redemption counts |
| GET | `/campaigns/by-client/{clientCode}/stats` | Same, per client |

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

Note the asymmetry that remains: voiding a redeemed voucher gives the user their
slot back but does **not** return stock to the campaign, and `/void` does not
check the voucher's current status. That is pre-existing behaviour, left as-is.

The cap is enforced inside the redemption transaction, which locks the voucher
row and then the campaign row until commit. Concurrent redemptions by the same
user therefore serialize rather than racing past the check.

## Failure reasons

Failed redemptions return `result: "FAILED"` with a machine-readable `reason`
alongside the human-readable `message`:

| `reason` | Meaning |
|---|---|
| `VOUCHER_NOT_FOUND` | No voucher with that code |
| `VOUCHER_ALREADY_REDEEMED` | Voucher is already `REDEEMED` |
| `VOUCHER_VOID` | Voucher is `VOID` |
| `CAMPAIGN_INACTIVE` | Campaign is not active |
| `USER_LIMIT_REACHED` | User is at the campaign's `max_per_user` cap |
| `CAMPAIGN_OUT_OF_STOCK` | No remaining stock |

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

Each test method starts from a freshly rebuilt database — the test class carries
`@Sql({"/schema.sql", "/data.sql"})`, which drops, recreates and re-seeds before
every method. Execution order therefore cannot matter.

Re-seeding is used instead of wrapping each test in a rolled-back transaction
because a test-managed transaction would be invisible to the other threads in
the concurrency test, and an `AFTER_COMMIT` listener never fires for a
transaction that rolls back. Both would have been silently disabled.
