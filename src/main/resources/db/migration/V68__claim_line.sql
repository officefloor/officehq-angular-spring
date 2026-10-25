-- A draft claim's lines, in the order they were added (position 0 the first): a label, a whole
-- quantity of at least 1 and a unit amount with two decimals. A claim with lines has as its amount
-- the quantities times the unit amounts added up.
CREATE TABLE claim_line (
    claim_id BIGINT         NOT NULL REFERENCES claim (id),
    position INT            NOT NULL,
    label    VARCHAR(200)   NOT NULL,
    qty      INT            NOT NULL,
    unit     DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (claim_id, position)
);
