-- A statement binds each of its person's counting (OPEN or SETTLED) claims to the figure it had
-- then: a stated claim shows and totals stated_due (null: n/a) until the person's next statement,
-- which rebinds their counting claims and leaves the rest unbound (stated false, live again).
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(12, 2);

-- The statements taken of a person, in the order they were taken (by id): their due-total then
-- (null: n/a), how many of their claims counted, and the sheet's today when it was taken.
CREATE TABLE statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    due_total DECIMAL(12, 2),
    claims    INT            NOT NULL,
    taken_on  DATE           NOT NULL,
    taken_by  VARCHAR(255)   NOT NULL
);
CREATE INDEX statement_sheet ON statement (sheet_id, person_id, id);
