-- A person's aliases: other names they go by. alias_key is the alias trimmed and lower-cased, as
-- names are compared; no two aliases on a sheet share one, nor does an alias share a person's name.
CREATE TABLE person_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    alias VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) NOT NULL,
    made_on DATE
);
CREATE UNIQUE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
CREATE INDEX person_alias_person ON person_alias (person_id, id);
