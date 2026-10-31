-- The people a person names as their patrons: each pair once, and never a person as their own patron.
CREATE TABLE patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id),
    CONSTRAINT patron_not_self CHECK (person_id <> patron_id)
);
CREATE UNIQUE INDEX patron_pair ON patron (person_id, patron_id);
CREATE INDEX patron_sheet ON patron (sheet_id, id);
