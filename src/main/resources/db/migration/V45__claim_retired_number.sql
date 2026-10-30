-- A place in a period sequence that a claim left when it was re-dated into another period: it is never
-- given again, so the next number of that sequence counts it as taken.
CREATE TABLE claim_retired_number (
    sheet_id VARCHAR(100) NOT NULL,
    period_sequence VARCHAR(30) NOT NULL,
    period_seq_number INT NOT NULL,
    PRIMARY KEY (sheet_id, period_sequence, period_seq_number)
);
