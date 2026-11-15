-- A claim number left behind when a re-dated claim moved to another period and took the next
-- number there: it is never given to a claim again.
CREATE TABLE retired_number (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_number VARCHAR(120)   NOT NULL
);
CREATE INDEX retired_number_sheet ON retired_number (sheet_id);
