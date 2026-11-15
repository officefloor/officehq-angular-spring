-- Each person's opening balance for a period: pasted amounts added to the carried figure they
-- show for it. Pasting again for the same person and period adds to the amount held.
CREATE TABLE period_person_opening (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL,
    amount    DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
