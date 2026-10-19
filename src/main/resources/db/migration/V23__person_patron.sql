-- The people a person has named as their patrons, each at most once and never themselves.
CREATE TABLE person_patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id),
    CONSTRAINT person_patron_once UNIQUE (person_id, patron_id),
    CONSTRAINT person_patron_not_self CHECK (person_id <> patron_id)
);
CREATE INDEX person_patron_sheet ON person_patron (sheet_id, person_id);
