-- A claim's lines, one row each, in the order they were added by id: a label, a whole quantity of at
-- least 1 and a unit amount (two decimals). A claim with lines has the quantities times the unit
-- amounts, added up, as its amount.
CREATE TABLE claim_line (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id  BIGINT NOT NULL REFERENCES claim (id),
    label     VARCHAR(200) NOT NULL,
    qty       INT NOT NULL,
    unit      DECIMAL(12, 2) NOT NULL
);
CREATE INDEX claim_line_claim ON claim_line (claim_id);
