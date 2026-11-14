-- A statement taken of a person: their due-total at that moment (null when it was n/a), how many
-- of their claims counted then, and the sheet's today when it was taken. A person shows how many
-- statements they have had and their last one's due-total and date.
CREATE TABLE person_statement (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    due_total  DECIMAL(15, 2),
    claims     INT            NOT NULL,
    taken_on   DATE           NOT NULL,
    taken_by   VARCHAR(100)   NOT NULL
);
CREATE INDEX person_statement_person ON person_statement (person_id, id);
-- A statement binds each claim of the person that counts then to the due it shows then: the claim
-- shows and totals that figure (null: n/a) until the person's next statement rebinds it, or leaves
-- it unbound (live again) when it no longer counts. A settled claim's fixed due still wins.
ALTER TABLE claim ADD COLUMN stated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(15, 2);
