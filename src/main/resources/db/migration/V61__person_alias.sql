-- A person's aliases, stored trimmed: other names the person is found by. An alias shares the names'
-- uniqueness on the sheet, without regard to case.
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL
);
CREATE INDEX person_alias_person ON person_alias (person_id);
