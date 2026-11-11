-- The last rollover on each sheet, kept so its steward can undo it on the same sheet-today: who made
-- it, the sheet's today then, the fiscal year rolled over, the periods it generated and the opening
-- balances it set (each person's amount added to the period it went into).
CREATE TABLE rollover_last (
    sheet_id   VARCHAR(100) NOT NULL PRIMARY KEY REFERENCES sheet (id),
    made_by    VARCHAR(100) NOT NULL,
    made_on    DATE         NOT NULL,
    fy         VARCHAR(20)  NOT NULL,
    opening_period_id BIGINT
);
CREATE TABLE rollover_last_period (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES rollover_last (sheet_id),
    period_id BIGINT       NOT NULL,
    PRIMARY KEY (sheet_id, period_id)
);
CREATE TABLE rollover_last_opening (
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES rollover_last (sheet_id),
    person_id BIGINT         NOT NULL,
    amount    DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (sheet_id, person_id)
);
