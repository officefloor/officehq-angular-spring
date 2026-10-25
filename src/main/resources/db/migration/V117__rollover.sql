-- Each rollover of a sheet's fiscal year, so the last one can be undone: who made it, on which sheet-today,
-- whether it was undone, the period it set opening balances in (null when it set none), the periods it
-- generated and the opening balance it added for each person. Period and person ids are kept without
-- references: a period undone is removed, and a person may leave the sheet.
CREATE TABLE rollover (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id          VARCHAR(100) NOT NULL REFERENCES sheet (id),
    made_by           VARCHAR(100) NOT NULL,
    made_on           DATE         NOT NULL,
    fiscal_year       INT          NOT NULL,
    opening_period_id BIGINT,
    undone            BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX rollover_sheet ON rollover (sheet_id, id);

CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    period_id   BIGINT NOT NULL,
    PRIMARY KEY (rollover_id, period_id)
);

CREATE TABLE rollover_opening (
    rollover_id BIGINT         NOT NULL REFERENCES rollover (id),
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (rollover_id, person_id)
);
