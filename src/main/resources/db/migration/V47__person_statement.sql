-- Each statement taken for a person, numbered from 1 in the order taken, with its due-total (null
-- when n/a) and date (the sheet's today). Cancelling the last statement removes its row, and the
-- person shows the one before it again. A person's last statement taken before this table is kept
-- here; any earlier ones were not recorded, so cancelling back past it shows no statement.
CREATE TABLE person_statement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)    NOT NULL REFERENCES sheet (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    seq       INT            NOT NULL,
    total     DECIMAL(14, 2),
    taken_on  DATE           NOT NULL,
    UNIQUE (person_id, seq)
);
INSERT INTO person_statement (sheet_id, person_id, seq, total, taken_on)
    SELECT sheet_id, id, statement_count, statement_total, statement_date
    FROM person WHERE statement_count > 0 AND statement_date IS NOT NULL;
