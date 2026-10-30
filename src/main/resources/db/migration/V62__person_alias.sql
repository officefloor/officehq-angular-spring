-- A person's aliases: stored trimmed; an alias shares the names' uniqueness on the sheet (compared
-- without regard to case, against every name and alias), checked as aliases are added.
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL
);
CREATE INDEX person_alias_person ON person_alias (person_id);
