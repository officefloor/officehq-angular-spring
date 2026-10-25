-- A person may have aliases. An alias shares the names' uniqueness on the sheet: compared without
-- regard to case and ignoring surrounding spaces, it equals no name and no other alias there. Aliases
-- are stored trimmed; alias_key is the trimmed, lower-cased alias the uniqueness among aliases is kept on.
CREATE TABLE person_alias (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    alias     VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
