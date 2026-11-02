-- A person's aliases: each stored with surrounding spaces removed. An alias shares the names'
-- uniqueness on the sheet (compared without regard to case), which the service keeps.
CREATE TABLE person_alias (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    alias     VARCHAR(200) NOT NULL,
    PRIMARY KEY (person_id, alias)
);
