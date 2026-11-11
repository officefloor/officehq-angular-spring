-- The people a person names as their patrons, each another person on the same sheet, named at most
-- once. A person's backing is their patrons' due-totals added up.
CREATE TABLE person_patron (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    patron_id BIGINT       NOT NULL REFERENCES person (id)
);
CREATE UNIQUE INDEX person_patron_pair ON person_patron (person_id, patron_id);
CREATE INDEX person_patron_sheet ON person_patron (sheet_id, id);
