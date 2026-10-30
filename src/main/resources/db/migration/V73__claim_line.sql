-- A DRAFT claim's lines, in the order they were added: a label, a quantity (a whole number, at least 1)
-- and a unit amount (two decimals). A claim with lines has the quantities times the unit amounts added up
-- as its amount. A claim already there starts with none.
CREATE TABLE claim_line (
    claim_id BIGINT NOT NULL REFERENCES claim(id),
    seq INT NOT NULL,
    label VARCHAR(200) NOT NULL,
    qty INT NOT NULL,
    unit NUMERIC(14, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
