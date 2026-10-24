-- The claim templates on a sheet, one row each by name: a template's amount and note are copied into
-- a DRAFT claim added from it, so a template changed or saved again later leaves those claims as they were.
CREATE TABLE sheet_claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200)   NOT NULL,
    amount   DECIMAL(12, 2) NOT NULL,
    note     VARCHAR(500)   NOT NULL,
    CONSTRAINT sheet_claim_template_unique UNIQUE (sheet_id, name)
);
