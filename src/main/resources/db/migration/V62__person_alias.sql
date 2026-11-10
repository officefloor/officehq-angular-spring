-- A person's aliases, stored trimmed. An alias shares the names' uniqueness on the sheet (compared
-- without regard to case, by the lower-cased key): no alias equals any name or other alias there.
CREATE TABLE person_alias (
    person_id BIGINT NOT NULL REFERENCES person (id),
    alias VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) GENERATED ALWAYS AS (LOWER(alias)),
    CONSTRAINT person_alias_once UNIQUE (person_id, alias_key)
);
