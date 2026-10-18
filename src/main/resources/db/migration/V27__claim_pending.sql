-- A claim a clerk settles whose due is above the sheet's clerk limit goes to PENDING: OPEN -> PENDING,
-- then a steward approves it (-> SETTLED) or rejects it (-> OPEN). A PENDING claim counts as open. The
-- status column is rebuilt so its check allows PENDING (the old check has a generated name).
ALTER TABLE claim ADD COLUMN status_new VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status_new IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET status_new = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN status_new RENAME TO status;
