-- A sheet's claim templates: a name, an amount and a note a claim can be added from. A name is on a
-- sheet at most once; saving it again replaces the template's amount and note. A claim added from
-- a template copies them, so a later change to the template leaves it as it was.
CREATE TABLE claim_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    name VARCHAR(255) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    note VARCHAR(2000) NOT NULL
);
CREATE UNIQUE INDEX claim_template_sheet_name ON claim_template (sheet_id, name);
