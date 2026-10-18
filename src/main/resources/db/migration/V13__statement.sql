-- A statement taken for a person: the sheet's today when it was taken, their due-total then (null:
-- n/a) and how many of their claims counted. A person's statements are listed in the order taken (id).
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    taken_on  DATE           NOT NULL,
    due_total DECIMAL(15, 2),
    claims    INT            NOT NULL,
    taken_by  VARCHAR(200)   NOT NULL
);
CREATE INDEX statement_person ON statement (sheet_id, person_id, id);
-- A claim bound by its person's last statement shows and totals the figure it had then (stated_due,
-- null: n/a) until the next statement rebinds or unbinds it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(15, 2);
