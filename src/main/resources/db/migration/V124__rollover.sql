-- Each rollover made on a sheet, so the last one can be undone on the sheet-today it was made: who
-- made it, on which sheet-today, the fiscal year it rolled over, and whether it has been undone.
CREATE TABLE rollover (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL,
    made_by   VARCHAR(100) NOT NULL,
    made_on   DATE         NOT NULL,
    fy        VARCHAR(10)  NOT NULL,
    -- the new year's first period, whose opening balances it added to
    opening_period_id BIGINT NOT NULL,
    undone    BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX rollover_sheet ON rollover (sheet_id, id);
-- The periods a rollover generated.
CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    period_id   BIGINT NOT NULL,
    PRIMARY KEY (rollover_id, period_id)
);
-- The opening balance a rollover added for each person to the new year's first period.
CREATE TABLE rollover_opening (
    rollover_id BIGINT         NOT NULL REFERENCES rollover (id),
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (rollover_id, person_id)
);
