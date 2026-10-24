-- A person's aliases: other names they go by. Each is kept without the spaces around it; alias_key
-- is the alias as names are compared (in lower case), and no alias equals any name or alias on the
-- person's sheet.
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) NOT NULL
);
CREATE INDEX person_alias_person ON person_alias (person_id);
CREATE INDEX person_alias_key ON person_alias (alias_key);
