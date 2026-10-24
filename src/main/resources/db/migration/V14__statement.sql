-- The statements taken of a person: the sheet's today when each was taken, the person's due-total
-- then and how many of their claims counted toward it.
CREATE TABLE statement (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id      BIGINT         NOT NULL REFERENCES person (id),
    statement_date DATE           NOT NULL,
    due_total      DECIMAL(14, 2) NOT NULL,
    claim_count    INT            NOT NULL
);
CREATE INDEX statement_sheet ON statement (sheet_id, id);
