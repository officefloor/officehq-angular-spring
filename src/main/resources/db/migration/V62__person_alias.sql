-- A person may have aliases, each stored without surrounding spaces. An alias is unique on its sheet, ignoring case,
-- as names are; the store keeps an alias from equalling a name.
CREATE TABLE person_alias (
    person_id BIGINT NOT NULL REFERENCES person (id),
    sheet_id VARCHAR(100) NOT NULL,
    alias VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(alias))
);
CREATE UNIQUE INDEX person_alias_sheet_alias ON person_alias (sheet_id, alias_key);
