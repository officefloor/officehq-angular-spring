-- A claim may now be PENDING: settled by a clerk above the clerk limit, awaiting a steward's approval.
-- It counts toward what is claimed and due, like an OPEN one.
ALTER TABLE claim ADD COLUMN next_status VARCHAR(16) DEFAULT 'DRAFT' NOT NULL
    CHECK (next_status IN ('DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'));
UPDATE claim SET next_status = status;
ALTER TABLE claim DROP COLUMN status;
ALTER TABLE claim ALTER COLUMN next_status RENAME TO status;
