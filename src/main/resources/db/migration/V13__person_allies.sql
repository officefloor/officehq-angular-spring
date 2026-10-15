-- Two people may be allies; the link goes both ways, so each pair is stored once, in the order it was given.
CREATE TABLE person_ally (
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    ally_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    PRIMARY KEY (person_id, ally_id)
);
