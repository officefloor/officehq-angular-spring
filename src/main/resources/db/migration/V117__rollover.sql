-- Each rollover of a fiscal year on a sheet: the old fiscal year (FYyyyy), who made it and the sheet's today then, and
-- whether it has been undone. Kept so the last rollover can be undone on the day it was made.
CREATE TABLE rollover (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    fy       VARCHAR(10)  NOT NULL,
    made_by  VARCHAR(100) NOT NULL,
    made_on  DATE         NOT NULL,
    undone   BOOLEAN      NOT NULL DEFAULT FALSE
);

-- The periods a rollover generated, by id.
CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    period_id   BIGINT NOT NULL,
    PRIMARY KEY (rollover_id, period_id)
);

-- The opening balances a rollover added: to which period, for which person, how much.
CREATE TABLE rollover_opening (
    rollover_id BIGINT         NOT NULL REFERENCES rollover (id),
    period_id   BIGINT         NOT NULL,
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (rollover_id, period_id, person_id)
);
CREATE INDEX rollover_sheet ON rollover (sheet_id);
