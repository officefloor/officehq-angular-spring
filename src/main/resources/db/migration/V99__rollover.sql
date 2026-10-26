-- Each rollover of a fiscal year: who made it, on which sheet's today, the periods it generated and the
-- opening balances it added (to the new year's first period), so the last one can be undone on the same
-- today by the one who made it. An undone rollover keeps its row, marked undone.
CREATE TABLE rollover (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100) NOT NULL REFERENCES sheet (id),
    fy_year        INT          NOT NULL,
    made_by        VARCHAR(200) NOT NULL,
    made_on        DATE         NOT NULL,
    opening_period BIGINT,
    undone         BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id) ON DELETE CASCADE,
    period_id   BIGINT NOT NULL,
    PRIMARY KEY (rollover_id, period_id)
);

CREATE TABLE rollover_opening (
    rollover_id BIGINT         NOT NULL REFERENCES rollover (id) ON DELETE CASCADE,
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (rollover_id, person_id)
);
