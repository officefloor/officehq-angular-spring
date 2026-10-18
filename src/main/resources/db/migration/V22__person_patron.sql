-- A person's patrons: other people on the same sheet they have named, each at most once.
CREATE TABLE person_patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id),
    CHECK (person_id <> patron_id)
);
CREATE UNIQUE INDEX person_patron_pair ON person_patron (person_id, patron_id);
CREATE INDEX person_patron_sheet ON person_patron (sheet_id, id);
