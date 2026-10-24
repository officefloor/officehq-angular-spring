-- The patrons a person has named: other people on the same sheet, each at most once.
CREATE TABLE person_patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id),
    CONSTRAINT person_patron_unique UNIQUE (person_id, patron_id)
);
CREATE INDEX person_patron_sheet ON person_patron (sheet_id, id);
