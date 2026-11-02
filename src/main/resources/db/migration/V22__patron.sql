-- A person's patrons: other people on the same sheet they have named, each at most once.
CREATE TABLE patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id),
    CONSTRAINT patron_not_self CHECK (person_id <> patron_id),
    CONSTRAINT patron_unique UNIQUE (person_id, patron_id)
);
CREATE INDEX patron_sheet ON patron (sheet_id, id);
