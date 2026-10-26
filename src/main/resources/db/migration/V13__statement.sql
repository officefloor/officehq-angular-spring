-- Whether a claim is bound by its person's last statement, and the figure it is bound to (null for n/a).
-- A bound claim shows and totals that figure until the person's next statement rebinds or frees it.
ALTER TABLE claim ADD COLUMN stated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);

-- The statements taken for a person, in the order taken (by id): the due-total then (null for n/a),
-- the count of claims that counted and the sheet's today when it was taken.
CREATE TABLE statement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id   BIGINT         NOT NULL REFERENCES person (id),
    due_total   DECIMAL(14, 2),
    claim_count INT            NOT NULL,
    taken_on    DATE           NOT NULL
);
CREATE INDEX statement_sheet ON statement (sheet_id, person_id, id);
