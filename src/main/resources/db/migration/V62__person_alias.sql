-- A person's aliases, kept trimmed. An alias shares the names' uniqueness on the sheet (ignoring
-- case): it equals no name or alias of anyone on the sheet; the service enforces that, and
-- alias_key keeps one person from holding the same alias twice.
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(alias)),
    PRIMARY KEY (person_id, alias)
);
CREATE UNIQUE INDEX person_alias_key ON person_alias (person_id, alias_key);
