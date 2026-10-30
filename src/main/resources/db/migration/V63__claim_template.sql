-- The claim templates on a sheet, each name once: an amount and a note a person's claim can be added with.
CREATE TABLE sheet_claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name     VARCHAR(100) NOT NULL,
    amount   DECIMAL(14, 2) NOT NULL,
    note     VARCHAR(500) NOT NULL,
    CONSTRAINT sheet_claim_template_unique UNIQUE (sheet_id, name)
);
