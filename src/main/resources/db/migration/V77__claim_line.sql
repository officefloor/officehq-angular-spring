-- A DRAFT claim's lines, in the order of their ids. Each keeps a label, a quantity (a whole number, at least 1) and
-- a unit amount (two decimals); a claim with lines has the quantities times the unit amounts added up as its amount.
CREATE TABLE claim_line (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id BIGINT         NOT NULL REFERENCES claim (id),
    label    VARCHAR(255)   NOT NULL,
    qty      INT            NOT NULL CHECK (qty >= 1),
    unit     DECIMAL(12, 2) NOT NULL CHECK (unit >= 0)
);
CREATE INDEX claim_line_sheet ON claim_line (sheet_id, id);
CREATE INDEX claim_line_claim ON claim_line (claim_id, id);
