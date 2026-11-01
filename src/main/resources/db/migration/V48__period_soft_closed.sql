-- A period can be soft-closed: its due-total and each person's due-total for it are fixed at that
-- moment, as for a close, but claims dated inside it can still be added, edited, opened, settled
-- and voided. A soft-closed period is not closed; it can be closed (fixing the figures afresh) or
-- reopened.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN NOT NULL DEFAULT FALSE;
-- A claim added while the period its date falls in was soft-closed is a late entry.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN NOT NULL DEFAULT FALSE;
