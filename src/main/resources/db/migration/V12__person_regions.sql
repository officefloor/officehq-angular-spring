-- A person may belong to any number of regions (short codes like N1 or S2).
CREATE TABLE person_region (
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    region VARCHAR(16) NOT NULL,
    PRIMARY KEY (person_id, region)
);
INSERT INTO person_region (person_id, region)
    SELECT id, region FROM person WHERE region IS NOT NULL;
ALTER TABLE person DROP COLUMN region;
