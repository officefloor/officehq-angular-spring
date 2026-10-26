-- The sheet's claim templates, each name at most once: a name, an amount and a note a claim can be added from.
-- A claim made from one copies its amount and note, so a later change to the template leaves it as it was.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    name     VARCHAR(140)   NOT NULL,
    amount   DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    note     VARCHAR(500)   NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX claim_template_name ON claim_template (sheet_id, name);
