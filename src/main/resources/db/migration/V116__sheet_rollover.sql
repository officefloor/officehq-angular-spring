-- The sheet's last rollover, kept so its steward can undo it on the same sheet-today: the fiscal year
-- rolled over, who made it and on what today, the periods it generated, and the opening balances it
-- added to the new year's first period (opening_period_id), by person. A new rollover replaces it;
-- undoing it removes it.
CREATE TABLE sheet_rollover (
    sheet_id          VARCHAR(100) NOT NULL PRIMARY KEY REFERENCES sheet (id),
    fiscal_year       INT          NOT NULL,
    made_by           VARCHAR(100) NOT NULL,
    made_on           DATE         NOT NULL,
    opening_period_id BIGINT
);

CREATE TABLE sheet_rollover_period (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet_rollover (sheet_id),
    period_id BIGINT       NOT NULL
);

CREATE TABLE sheet_rollover_opening (
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet_rollover (sheet_id),
    person_id BIGINT         NOT NULL,
    amount    DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (sheet_id, person_id)
);
