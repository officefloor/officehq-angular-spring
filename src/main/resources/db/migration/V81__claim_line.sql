-- A draft claim's lines, in the order they were added (by id). Each keeps its (trimmed, never
-- empty) label, its quantity (a whole number, at least 1) and its unit amount (two decimals); a
-- claim with lines has their quantities times unit amounts added up as its amount.
CREATE TABLE claim_line (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT         NOT NULL REFERENCES claim (id),
    label     VARCHAR(200)   NOT NULL,
    qty       INT            NOT NULL,
    unit      DECIMAL(14, 2) NOT NULL
);
CREATE INDEX claim_line_claim ON claim_line (claim_id, id);
