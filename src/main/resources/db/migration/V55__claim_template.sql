-- The sheet's claim templates: a name (stored trimmed, once per sheet), an amount and a note. Saving
-- a name again replaces its amount and note; a claim made from a template copies them, so it keeps
-- them whatever the template becomes.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    amount   DECIMAL(12, 2) NOT NULL,
    note     VARCHAR(1000) NOT NULL
);
CREATE UNIQUE INDEX claim_template_once ON claim_template (sheet_id, name);
