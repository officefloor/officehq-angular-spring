-- A claim whose due is above the sheet's review threshold in force on its date goes to REVIEW when opened,
-- until a steward reviews it (OPEN, numbered then); it can be voided. The status check is re-made with it.
ALTER TABLE claim ALTER COLUMN status RENAME TO status_old;
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'REVIEW', 'PENDING', 'SETTLED', 'VOID', 'DELETED'));
UPDATE claim SET status = status_old;
ALTER TABLE claim DROP COLUMN status_old;
