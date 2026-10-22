-- A claim template on a sheet: a name (trimmed, once per sheet), an amount and a note. Saving a name
-- again replaces its amount and note. A claim made from one copies them, so later changes leave it be.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(64)    NOT NULL REFERENCES sheet (id),
    name     VARCHAR(100)   NOT NULL,
    amount   DECIMAL(14, 2) NOT NULL,
    note     VARCHAR(500)   NOT NULL
);
CREATE UNIQUE INDEX claim_template_sheet_name ON claim_template (sheet_id, name);
