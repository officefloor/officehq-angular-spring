-- A claim's lines, in the order added (by seq): each with a label, a quantity and a unit amount; a claim with
-- lines has as its amount the quantities times the unit amounts added up.
CREATE TABLE claim_line (
    claim_id   BIGINT         NOT NULL REFERENCES claim (id),
    seq        INT            NOT NULL,
    line_label VARCHAR(200)   NOT NULL,
    qty        INT            NOT NULL,
    unit       DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
