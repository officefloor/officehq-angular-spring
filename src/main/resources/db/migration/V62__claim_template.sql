-- The claim templates on a sheet: a name, an amount and a note a person's row adds a DRAFT claim from. Each name is on
-- a sheet at most once; saving it again replaces its amount and note. A claim made from one copies its values, so it
-- is not tied to the template afterwards.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200)   NOT NULL,
    amount   DECIMAL(14, 2) NOT NULL,
    note     VARCHAR(1000)  NOT NULL
);
CREATE UNIQUE INDEX claim_template_sheet_name ON claim_template (sheet_id, name);
