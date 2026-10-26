-- A period number a claim gave up when it was re-dated into another period: never given again, so the next number
-- of that sequence counts past it.
CREATE TABLE claim_number_retired (
    sheet_id VARCHAR(100) NOT NULL,
    number_sequence VARCHAR(120) NOT NULL,
    number_in_sequence BIGINT NOT NULL,
    PRIMARY KEY (sheet_id, number_sequence, number_in_sequence)
);
