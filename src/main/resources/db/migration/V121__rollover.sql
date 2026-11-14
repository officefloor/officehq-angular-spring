-- A steward's rollover of a fiscal year, kept so the last one can be undone on the sheet's today it
-- was made, by the steward who made it: the periods it generated and the opening balances it set on
-- the new year's first period. Undoing it removes those periods and takes the openings back, and
-- marks it undone.
CREATE TABLE rollover (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    fiscal_year INT NOT NULL,
    made_by VARCHAR(200) NOT NULL,
    made_on DATE NOT NULL,
    opening_period_id BIGINT,
    undone BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX rollover_sheet ON rollover (sheet_id, id);
CREATE TABLE rollover_period (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    period_id BIGINT NOT NULL
);
CREATE TABLE rollover_opening (
    rollover_id BIGINT NOT NULL REFERENCES rollover (id),
    person_id BIGINT NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (rollover_id, person_id)
);
