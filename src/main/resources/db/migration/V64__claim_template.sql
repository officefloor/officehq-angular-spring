-- The sheet's claim templates, each name once: a DRAFT claim made from one takes its amount and note then.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200)   NOT NULL,
    amount   DECIMAL(14, 2) NOT NULL,
    note     VARCHAR(500)   NOT NULL
);
CREATE UNIQUE INDEX claim_template_unique ON claim_template (sheet_id, name);
