-- A person's patrons: other people on the same sheet, each named at most once. A person shows
-- their patrons' names and their backing (the patrons' due-totals added up, n/a ones skipped).
CREATE TABLE person_patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id),
    CHECK (patron_id <> person_id),
    CONSTRAINT person_patron_once UNIQUE (person_id, patron_id)
);
CREATE INDEX person_patron_sheet ON person_patron (sheet_id, id);
