CREATE TABLE person_patron (
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    patron_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    PRIMARY KEY (person_id, patron_id)
);
INSERT INTO person_patron (person_id, patron_id)
    SELECT id, patron_id FROM person WHERE patron_id IS NOT NULL;
ALTER TABLE person DROP COLUMN patron_id;
