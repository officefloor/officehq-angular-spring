-- A statement taken of a person: their due-total then (two decimals, or n/a), how many of their
-- claims counted, and the sheet's today when it was taken. A person's statements are in the order
-- of their ids; the last one is the person's last statement.
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    taken_on  DATE         NOT NULL,
    due_total VARCHAR(20)  NOT NULL,
    claims    INT          NOT NULL
);
CREATE INDEX statement_person ON statement (person_id, id);

-- Whether the claim is bound by its person's last statement, and the due it was bound to (null when
-- it had none then: n/a). A bound claim shows and totals that due until the next statement.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);
