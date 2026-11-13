-- Each claim's lines: a label, a whole quantity of at least 1 and a unit amount (two decimals), in the order
-- added. Existing claims start with none.
CREATE TABLE claim_line (
    claim_id    BIGINT         NOT NULL REFERENCES claim (id),
    seq         INT            NOT NULL,
    line_label  VARCHAR(200)   NOT NULL,
    qty         INT            NOT NULL,
    unit_amount DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
