-- A person's patrons: other people on the same sheet they have named, each at most once.
CREATE TABLE person_patron (
    person_id BIGINT NOT NULL REFERENCES person (id),
    patron_id BIGINT NOT NULL REFERENCES person (id),
    PRIMARY KEY (person_id, patron_id),
    CHECK (person_id <> patron_id)
);
