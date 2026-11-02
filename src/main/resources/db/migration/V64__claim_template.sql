-- The claim templates of a sheet, each (trimmed) name at most once: saving a name again replaces
-- its amount and note. A claim made from a template copies them, so it keeps no link to it.
CREATE TABLE claim_template (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    name     VARCHAR(200)   NOT NULL,
    amount   DECIMAL(14, 2) NOT NULL,
    note     VARCHAR(500)   NOT NULL,
    CONSTRAINT claim_template_unique UNIQUE (sheet_id, name)
);
