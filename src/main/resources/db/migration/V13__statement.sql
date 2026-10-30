-- A claim bound by its person's last statement: it shows and totals the figure it had then
-- (stated_due, null when it had none) until the next statement rebinds or releases it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);

-- The statements taken for a person, in the order taken (by id): the sheet's today then, the
-- person's due-total then (null when it was n/a) and how many of their claims counted.
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    taken_on  DATE           NOT NULL,
    due_total DECIMAL(14, 2),
    claims    INT            NOT NULL
);
CREATE INDEX statement_person ON statement (sheet_id, person_id, id);
