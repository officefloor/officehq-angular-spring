-- A claim's figure bound by its person's last statement: stated is set on each claim that counted
-- when the statement was taken, with the due it had then (empty when it had none). The claim then
-- shows and totals that figure until the person's next statement rebinds or clears it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);

-- Each statement taken for a person: their due-total at that moment (empty when it was n/a) and the
-- sheet's today when it was taken.
CREATE TABLE statement (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id      BIGINT       NOT NULL REFERENCES person (id),
    due_total      DECIMAL(14, 2),
    statement_date DATE         NOT NULL,
    taken_by       VARCHAR(100) NOT NULL
);
CREATE INDEX statement_person ON statement (sheet_id, person_id, id);
