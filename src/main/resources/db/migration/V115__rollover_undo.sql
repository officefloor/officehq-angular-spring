-- The sheet's last rollover, kept so its steward can undo it on the sheet-today it was made: the
-- fiscal year rolled over, who made it and on which sheet-today, whether it has been undone, the
-- periods it generated, and the period it carried openings into (null when none) with the amount
-- it added to each person's opening balance there.
CREATE TABLE rollover (
    sheet_id         VARCHAR(100) NOT NULL PRIMARY KEY,
    fy               INT          NOT NULL,
    made_by          VARCHAR(100) NOT NULL,
    made_on          DATE         NOT NULL,
    undone           BOOLEAN      NOT NULL DEFAULT FALSE,
    opened_period_id BIGINT
);

CREATE TABLE rollover_period (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES rollover (sheet_id),
    period_id BIGINT       NOT NULL,
    PRIMARY KEY (sheet_id, period_id)
);

CREATE TABLE rollover_opening (
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES rollover (sheet_id),
    person_id BIGINT         NOT NULL,
    amount    DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (sheet_id, person_id)
);
