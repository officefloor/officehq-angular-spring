-- A period can be soft-closed: its due-total and each person's figure for it are fixed as on a close,
-- but claims dated inside it can still be added, edited, opened, settled and voided. A claim added
-- while its period is soft-closed is a late entry.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN NOT NULL DEFAULT FALSE;
