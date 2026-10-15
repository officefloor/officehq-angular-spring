-- A person may have any number of notes, each with a whole-number score; scores may repeat.
CREATE TABLE person_note (
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    position INT NOT NULL,
    score INT NOT NULL,
    PRIMARY KEY (person_id, position)
);
