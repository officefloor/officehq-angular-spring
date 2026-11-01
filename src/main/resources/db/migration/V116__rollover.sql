-- Each rollover of a sheet's fiscal year: the old fiscal year, the sheet's today it was made on and by whom, the
-- period it carried openings into, and whether it has been undone. Only the sheet's latest may be undone.
CREATE TABLE rollover (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id         VARCHAR(100) NOT NULL REFERENCES sheet (id),
    fiscal_year      INT          NOT NULL,
    made_on          DATE         NOT NULL,
    made_by          VARCHAR(100) NOT NULL,
    opening_period_id BIGINT,
    undone           BOOLEAN      NOT NULL DEFAULT FALSE
);

-- The periods a rollover generated.
CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    period_id   BIGINT NOT NULL,
    PRIMARY KEY (rollover_id, period_id)
);

-- The amount a rollover added to each person's opening balance for its opening period.
CREATE TABLE rollover_opening (
    rollover_id BIGINT         NOT NULL REFERENCES rollover (id),
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (rollover_id, person_id)
);
