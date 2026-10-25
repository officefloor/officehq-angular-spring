-- A clerk's settling of a claim whose due is above the clerk limit leaves it PENDING until a steward
-- approves it (SETTLED) or rejects it (OPEN again). PENDING counts like OPEN. The status's check has
-- no name to drop it by, so the status moves to a new column with the wider check.
ALTER TABLE claim ADD COLUMN claim_status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (claim_status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET claim_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN claim_status RENAME TO status;
