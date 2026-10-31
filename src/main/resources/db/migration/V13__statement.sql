-- Statements taken of a person: each records the person's due-total at that moment ('n/a' when
-- their grade had no rate then) and the sheet's today when it was taken.
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    due_total VARCHAR(20)  NOT NULL,
    taken_on  DATE         NOT NULL
);
CREATE INDEX statement_person ON statement (sheet_id, person_id, id);

-- A claim bound by its person's last statement: it shows and totals the figure it had then
-- (stated_due; none when it had no figure then, so it follows the rate as before) until the next
-- statement rebinds or unbinds it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);
