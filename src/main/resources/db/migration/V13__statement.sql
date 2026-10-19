-- A statement taken for a person: their due-total at that moment (none when it was n/a), how many of
-- their claims counted, and the sheet's today when it was taken. A person's statements are kept in the
-- order they were taken (by id).
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    taken_on  DATE           NOT NULL,
    taken_by  VARCHAR(255)   NOT NULL,
    due_total DECIMAL(14, 2),
    claims    INT            NOT NULL
);
CREATE INDEX statement_person ON statement (sheet_id, person_id, id);
-- A statement binds each of the person's claims that count to the figure it has then (none when it was
-- n/a); the claim shows that figure until the person's next statement, which rebinds it or, if it no
-- longer counts, leaves it unbound.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(12, 2);
