-- A lock anchor, one row per (campaign, user).
--
-- Redemption used to take a write lock on the campaign row, which serialised
-- every redemption on that campaign — on a flash sale, exactly when throughput
-- matters. Stock and the voucher claim are now atomic conditional UPDATEs that
-- need no lock at all, leaving only the per-user cap to guard: a check-then-act
-- that two concurrent requests from the SAME user could otherwise slip past.
--
-- Locking a row here narrows that serialisation from "everyone on the campaign"
-- to "one user on one campaign", which is a human double-clicking rather than a
-- queue of customers.
--
-- The table holds no state of its own; rows are created on demand and never
-- read for their contents.
CREATE TABLE user_campaign_allowance (
    campaign_id BIGINT      NOT NULL,
    user_id     VARCHAR(80) NOT NULL,
    PRIMARY KEY (campaign_id, user_id)
);
