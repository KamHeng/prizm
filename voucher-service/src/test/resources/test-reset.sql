-- Clears all data before each test, leaving the Flyway-created schema intact.
--
-- DELETE rather than DROP/CREATE on purpose: the schema has exactly one source
-- of truth (db/migration), so the tests cannot drift from the real DDL the way
-- a duplicated CREATE TABLE in test resources would.
DELETE FROM redemption;
DELETE FROM voucher;
DELETE FROM campaign;
