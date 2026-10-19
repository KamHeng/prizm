-- Sample data for local development and for the test suite. INSERTs only: it
-- creates no schema and drops nothing, so it cannot destroy data.
--
-- This never runs unless something asks for it: the default profile sets
-- spring.sql.init.mode=never, the `dev` profile points data-locations here, and
-- the tests load it through @Sql. It is packaged in the jar but inert.

INSERT INTO campaign (id, name, client_code, total_stock, remaining_stock, max_per_user, active) VALUES
  (1, 'Raya Flash Deal', 'ACME', 100, 100, 2, TRUE),
  (2, 'Merdeka Giveaway', 'NOVA', 50, 50, 2, TRUE),
  (3, 'Expired Test Campaign', 'ACME', 10, 0, 2, FALSE),
  (4, 'Limit Test Campaign', 'ZETA', 18, 18, 2, TRUE),
  -- Active but sold out: stock ran to zero while unredeemed codes remain, so
  -- this is the only campaign that reaches the out-of-stock check (campaign 3
  -- is inactive, so it short-circuits earlier).
  (5, 'Sold Out Campaign', 'ZETA', 2, 0, 2, TRUE),
  -- Limits other than 2, so nothing can pass by hardcoding the number.
  (6, 'Single Per User Campaign', 'ZETA', 8, 8, 1, TRUE),
  (7, 'Three Per User Campaign', 'ZETA', 6, 6, 3, TRUE);

-- Inserted without max_per_user on purpose: the only row that exercises the
-- column's DEFAULT 2 rather than an explicit value.
INSERT INTO campaign (id, name, client_code, total_stock, remaining_stock, active) VALUES
  (8, 'Default Limit Campaign', 'ZETA', 4, 4, TRUE);

-- One unit of stock but several live codes, so a burst of distinct users racing
-- for it must yield exactly one winner. Separate client code to keep it out of
-- the ZETA stats fixtures.
INSERT INTO campaign (id, name, client_code, total_stock, remaining_stock, max_per_user, active) VALUES
  (9, 'Last One Left Campaign', 'OMNI', 1, 1, 2, TRUE);

INSERT INTO voucher (id, campaign_id, code, status, redeemed_by, redeemed_at) VALUES
  (1, 1, 'RAYA-0001', 'ACTIVE',   NULL,      NULL),
  (2, 1, 'RAYA-0002', 'ACTIVE',   NULL,      NULL),
  (3, 1, 'RAYA-0003', 'ACTIVE',   NULL,      NULL),
  (4, 1, 'RAYA-0004', 'REDEEMED', 'user-99', '2026-06-01 10:15:00'),
  (5, 1, 'RAYA-0005', 'VOID',     'user-99', '2026-06-02 09:40:00'),
  (6, 1, 'RAYA-0006', 'ACTIVE',   NULL,      NULL),
  (7, 2, 'MRDK-0001', 'ACTIVE',   NULL,      NULL),
  (8, 2, 'MRDK-0002', 'ACTIVE',   NULL,      NULL),
  (9, 3, 'EXPD-0001', 'ACTIVE',   NULL,      NULL);

-- Campaign 4 exists purely so the per-user limit tests have their own voucher
-- pool, kept clear of the campaign 1 fixtures.
INSERT INTO voucher (id, campaign_id, code, status, redeemed_by, redeemed_at) VALUES
  (10, 4, 'LIMIT-0001', 'ACTIVE', NULL, NULL),
  (11, 4, 'LIMIT-0002', 'ACTIVE', NULL, NULL),
  (12, 4, 'LIMIT-0003', 'ACTIVE', NULL, NULL),
  (13, 4, 'LIMIT-0004', 'ACTIVE', NULL, NULL),
  (14, 4, 'LIMIT-0005', 'ACTIVE', NULL, NULL),
  (15, 4, 'LIMIT-0006', 'ACTIVE', NULL, NULL),
  (16, 4, 'LIMIT-0007', 'ACTIVE', NULL, NULL),
  (17, 4, 'LIMIT-0008', 'ACTIVE', NULL, NULL),
  (18, 4, 'LIMIT-0009', 'ACTIVE', NULL, NULL),
  (19, 4, 'LIMIT-0010', 'ACTIVE', NULL, NULL),
  (20, 4, 'LIMIT-0011', 'ACTIVE', NULL, NULL),
  (21, 4, 'LIMIT-0012', 'ACTIVE', NULL, NULL),
  (22, 4, 'LIMIT-0013', 'ACTIVE', NULL, NULL),
  (23, 4, 'LIMIT-0014', 'ACTIVE', NULL, NULL),
  (24, 4, 'LIMIT-0015', 'ACTIVE', NULL, NULL),
  (25, 4, 'LIMIT-0016', 'ACTIVE', NULL, NULL),
  (26, 4, 'LIMIT-0017', 'ACTIVE', NULL, NULL),
  (27, 4, 'LIMIT-0018', 'ACTIVE', NULL, NULL);

-- Campaign 5 (sold out). SOLD-0003/0004 are seeded as already redeemed by
-- user-sold-cap so that user sits at the cap on a campaign that is ALSO out of
-- stock — the only way to prove the limit check runs before the stock check.
INSERT INTO voucher (id, campaign_id, code, status, redeemed_by, redeemed_at) VALUES
  (28, 5, 'SOLD-0001', 'ACTIVE',   NULL,             NULL),
  (29, 5, 'SOLD-0002', 'ACTIVE',   NULL,             NULL),
  (30, 5, 'SOLD-0003', 'REDEEMED', 'user-sold-cap', '2026-06-10 11:00:00'),
  (31, 5, 'SOLD-0004', 'REDEEMED', 'user-sold-cap', '2026-06-10 11:05:00');

-- Campaign 6 (cap 1), campaign 7 (cap 3), campaign 8 (cap from DB default).
INSERT INTO voucher (id, campaign_id, code, status, redeemed_by, redeemed_at) VALUES
  (32, 6, 'ONE-0001',  'ACTIVE', NULL, NULL),
  (33, 6, 'ONE-0002',  'ACTIVE', NULL, NULL),
  (34, 6, 'ONE-0003',  'ACTIVE', NULL, NULL),
  (35, 6, 'ONE-0004',  'ACTIVE', NULL, NULL),
  (36, 6, 'ONE-0005',  'ACTIVE', NULL, NULL),
  (37, 6, 'ONE-0006',  'ACTIVE', NULL, NULL),
  (38, 7, 'TRIO-0001', 'ACTIVE', NULL, NULL),
  (39, 7, 'TRIO-0002', 'ACTIVE', NULL, NULL),
  (40, 7, 'TRIO-0003', 'ACTIVE', NULL, NULL),
  (41, 7, 'TRIO-0004', 'ACTIVE', NULL, NULL),
  (42, 8, 'DFLT-0001', 'ACTIVE', NULL, NULL),
  (43, 8, 'DFLT-0002', 'ACTIVE', NULL, NULL),
  (44, 8, 'DFLT-0003', 'ACTIVE', NULL, NULL);

-- Campaign 9: six live codes, one unit of stock.
INSERT INTO voucher (id, campaign_id, code, status, redeemed_by, redeemed_at) VALUES
  (45, 9, 'OVER-0001', 'ACTIVE', NULL, NULL),
  (46, 9, 'OVER-0002', 'ACTIVE', NULL, NULL),
  (47, 9, 'OVER-0003', 'ACTIVE', NULL, NULL),
  (48, 9, 'OVER-0004', 'ACTIVE', NULL, NULL),
  (49, 9, 'OVER-0005', 'ACTIVE', NULL, NULL),
  (50, 9, 'OVER-0006', 'ACTIVE', NULL, NULL);

-- user-99 has two log rows on campaign 1, but voucher 5 was later voided, so
-- only one of them counts toward the limit.
INSERT INTO redemption (voucher_id, campaign_id, user_id, created_at) VALUES
  (4, 1, 'user-99', '2026-06-01 10:15:00'),
  (5, 1, 'user-99', '2026-06-02 09:40:00');

-- Puts user-sold-cap at 2 of 2 on the sold-out campaign.
INSERT INTO redemption (voucher_id, campaign_id, user_id, created_at) VALUES
  (30, 5, 'user-sold-cap', '2026-06-10 11:00:00'),
  (31, 5, 'user-sold-cap', '2026-06-10 11:05:00');
