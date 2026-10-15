-- A person may be in any number of groups of their sheet, not just one.
CREATE TABLE person_membership (
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES person_group (id) ON DELETE CASCADE,
    PRIMARY KEY (person_id, group_id)
);
INSERT INTO person_membership (person_id, group_id) SELECT id, group_id FROM person WHERE group_id IS NOT NULL;
ALTER TABLE person DROP COLUMN group_id;
