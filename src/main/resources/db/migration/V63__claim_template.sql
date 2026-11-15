-- A claim template on the sheet: a name (at most once per sheet), the amount and note a claim made
-- from it is given. Saving a name again replaces its amount and note; claims made earlier keep theirs.
CREATE TABLE claim_template (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    name      VARCHAR(200) NOT NULL,
    amount    DECIMAL(15, 2) NOT NULL,
    note      VARCHAR(1000) NOT NULL,
    UNIQUE (sheet_id, name)
);
