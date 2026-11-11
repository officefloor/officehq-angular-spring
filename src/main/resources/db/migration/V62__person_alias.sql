-- Other names a person goes by. An alias is kept trimmed, with its key compared as names are: unique among the
-- names and aliases on its sheet.
CREATE TABLE person_alias (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
CREATE INDEX person_alias_person ON person_alias (person_id);
