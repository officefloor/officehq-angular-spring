-- A DRAFT claim's lines, in the order they were added (by id): a label (trimmed, never blank), a
-- quantity (a whole number, at least 1) and a unit amount (two decimals). A claim with lines has the
-- quantities times the unit amounts, added up, as its amount.
CREATE TABLE claim_line (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id   BIGINT         NOT NULL REFERENCES claim (id),
    label      VARCHAR(200)   NOT NULL,
    qty        INT            NOT NULL,
    unit       DECIMAL(14, 2) NOT NULL
);
CREATE INDEX claim_line_claim ON claim_line (claim_id, id);
