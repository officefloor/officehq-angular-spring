-- The people a person names as their patrons: others on the same sheet, each at most once.
CREATE TABLE person_patron (
    person_id BIGINT NOT NULL REFERENCES person (id),
    patron_id BIGINT NOT NULL REFERENCES person (id),
    PRIMARY KEY (person_id, patron_id)
);
