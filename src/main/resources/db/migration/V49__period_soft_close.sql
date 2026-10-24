-- Whether a closed period is only soft-closed: its due-total and each person's figure for it are fixed
-- as for a close, but claims dated in it can still be added, edited and change status. A claim added
-- while its period is soft-closed is a late entry.
ALTER TABLE period ADD COLUMN soft BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
