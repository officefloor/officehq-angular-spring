-- A draft claim's lines, in the order they were added: a label, a whole quantity of at least 1 and a unit amount.
-- A claim with lines has the sum of their quantities times unit amounts as its amount.
CREATE TABLE claim_line (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT         NOT NULL REFERENCES claim (id),
    label    VARCHAR(140)   NOT NULL,
    qty      INT            NOT NULL,
    unit     DECIMAL(14, 2) NOT NULL
);
CREATE INDEX claim_line_claim ON claim_line (claim_id, id);
