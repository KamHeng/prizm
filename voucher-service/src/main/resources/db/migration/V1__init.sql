-- Baseline schema. Flyway owns the schema from here on: add a V2__*.sql rather
-- than editing this file, which is checksummed once applied.
--
-- There are deliberately no DROP statements. The previous schema.sql dropped
-- all three tables on every startup, which was harmless only because the
-- datasource was in-memory.

CREATE TABLE campaign (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    client_code     VARCHAR(50)  NOT NULL,
    total_stock     INT          NOT NULL,
    remaining_stock INT          NOT NULL,
    max_per_user    INT          NOT NULL DEFAULT 2,
    active          BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE voucher (
    id          BIGINT PRIMARY KEY,
    campaign_id BIGINT       NOT NULL,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL,
    redeemed_by VARCHAR(80),
    redeemed_at TIMESTAMP
);

CREATE TABLE redemption (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_id  BIGINT      NOT NULL,
    campaign_id BIGINT      NOT NULL,
    user_id     VARCHAR(80) NOT NULL,
    created_at  TIMESTAMP   NOT NULL
);

CREATE INDEX idx_redemption_campaign_user ON redemption(campaign_id, user_id);
