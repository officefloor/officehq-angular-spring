-- A statement taken for a person: the sheet's today when it was taken, the person's due-total then
-- ("n/a" when their grade had no rate and no claim of theirs kept a figure) and how many of their
-- claims counted. A person's last statement is the one with the highest id.
CREATE TABLE statement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    taken_on DATE NOT NULL,
    due_total VARCHAR(20) NOT NULL,
    claims INT NOT NULL
);
CREATE INDEX statement_person ON statement (person_id, id);
CREATE INDEX statement_sheet ON statement (sheet_id, id);

-- A claim bound by its person's last statement shows and totals the figure it had then (null when
-- it had none) until the next statement rebinds or unbinds it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(12, 2);
