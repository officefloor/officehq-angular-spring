-- A place in a period's numbering given up by a claim re-dated into another period: never given again.
CREATE TABLE claim_number_retired (
    sheet_id VARCHAR(100) NOT NULL,
    number_period VARCHAR(200) NOT NULL,
    number_seq INT NOT NULL,
    PRIMARY KEY (sheet_id, number_period, number_seq)
);
