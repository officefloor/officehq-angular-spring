-- A person's aliases, each stored without surrounding spaces; alias_key is the trimmed, lower-cased
-- alias. An alias is unique on the sheet among aliases (here) and names (kept by the service).
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    alias     VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) NOT NULL
);
CREATE INDEX person_alias_person ON person_alias (person_id);
CREATE UNIQUE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
