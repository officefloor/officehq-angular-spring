-- The claim templates of a sheet, one per name: a claim added from one takes its amount and note at that moment.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200) NOT NULL,
    amount   DECIMAL(12, 2) NOT NULL,
    note     VARCHAR(1000) NOT NULL
);
CREATE UNIQUE INDEX claim_template_sheet_name ON claim_template (sheet_id, name);
