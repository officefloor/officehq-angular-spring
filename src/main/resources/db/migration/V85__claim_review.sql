-- A claim may now be REVIEW: opened while its due is above the sheet's review threshold in force on
-- its date, awaiting a steward's review to become OPEN. It counts nowhere and carries no number yet.
-- The review threshold is a dated setting (sheet_setting, key review-threshold); none before every
-- from-date.
ALTER TABLE claim ADD COLUMN next_status VARCHAR(16) DEFAULT 'DRAFT' NOT NULL
    CHECK (next_status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'DELETED', 'REVIEW'));
UPDATE claim SET next_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN next_status RENAME TO status;
