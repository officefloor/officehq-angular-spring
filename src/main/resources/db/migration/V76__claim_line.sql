-- A DRAFT claim's lines, in the order they were added: a label, a quantity (at least 1) and a unit amount.
CREATE TABLE claim_line (
    claim_id    BIGINT         NOT NULL REFERENCES claim (id),
    seq         INTEGER        NOT NULL,
    label       VARCHAR(200)   NOT NULL,
    qty         INTEGER        NOT NULL,
    unit_amount DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
