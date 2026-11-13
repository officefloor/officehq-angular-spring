-- A claim number given up when a re-dated claim took the next number of its new period: it is never
-- given to a claim again.
CREATE TABLE retired_claim_number (
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    claim_number VARCHAR(255) NOT NULL,
    PRIMARY KEY (sheet_id, claim_number)
);
