-- A statement taken of a person: the day it was taken (the sheet's today), who took it, their
-- due-total then (null when it was n/a) and how many of their claims counted.
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    taken_on  DATE           NOT NULL,
    taken_by  VARCHAR(100)   NOT NULL,
    due_total DECIMAL(14, 2),
    claims    INT            NOT NULL
);
CREATE INDEX statement_person ON statement (person_id, id);
CREATE INDEX statement_sheet ON statement (sheet_id, id);

-- Whether a claim is bound by its person's last statement, and the due it was bound to (null when
-- it had none then, so it stays live). The next statement rebinds or unbinds it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);
