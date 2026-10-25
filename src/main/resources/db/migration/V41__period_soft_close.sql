-- A period can be soft-closed: its due-total (closed_total) and each person's due-total for it
-- (period_person_total) are fixed as a close fixes them, but claims dated inside it can still be
-- added, edited, opened, settled and voided. A soft-closed period is not closed; it can be closed
-- (fixing the figures afresh) or reopened.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN DEFAULT FALSE NOT NULL;
-- A claim added while the period its date falls in was soft-closed is a late entry.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
