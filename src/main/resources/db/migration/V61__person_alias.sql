-- A person's aliases: other names they go by, each kept with surrounding spaces removed and with
-- its key (ignoring case). An alias shares the names' uniqueness on the sheet: no alias equals,
-- by key, any name or other alias there.
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL,
    alias_key VARCHAR(200) NOT NULL,
    PRIMARY KEY (person_id, alias_key)
);
