-- A sheet's last rollover, kept so it can be undone: who made it, the sheet's today then, the fiscal year rolled
-- over, the periods it generated and the opening balances it set in the new year's first period. One per sheet,
-- replaced by the next rollover and removed when undone.
CREATE TABLE rollover_last (
    sheet_id        VARCHAR(100) PRIMARY KEY REFERENCES sheet (id),
    made_by         VARCHAR(100) NOT NULL,
    made_on         DATE         NOT NULL,
    fy              VARCHAR(20)  NOT NULL,
    first_period_id BIGINT
);
CREATE TABLE rollover_last_period (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES rollover_last (sheet_id),
    period_id BIGINT       NOT NULL,
    PRIMARY KEY (sheet_id, period_id)
);
CREATE TABLE rollover_last_opening (
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES rollover_last (sheet_id),
    person_id BIGINT         NOT NULL,
    opening   DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (sheet_id, person_id)
);
