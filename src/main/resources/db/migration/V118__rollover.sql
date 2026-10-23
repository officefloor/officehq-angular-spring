-- A rollover of a fiscal year, kept so the last one can be undone: who made it, the sheet's today
-- then, the fiscal year rolled over, and whether it has been undone. The latest by id is the last.
CREATE TABLE rollover (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    made_by    VARCHAR(200) NOT NULL,
    made_on    DATE,
    fy         INT          NOT NULL,
    opening_period_id BIGINT,
    undone     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX rollover_sheet ON rollover (sheet_id, id);
-- The periods a rollover generated (no reference: undoing it removes them).
CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    period_id   BIGINT NOT NULL
);
-- The opening balances a rollover added, by person, to its opening period.
CREATE TABLE rollover_opening (
    rollover_id BIGINT         NOT NULL REFERENCES rollover (id),
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (rollover_id, person_id)
);
