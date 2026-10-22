-- A period may be soft-closed: its due-total and each person's figure for it are fixed (closed_total
-- and period_person_total, as on a close), while claims dated in it can still change. A claim added
-- while its period is soft-closed is a late entry.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN NOT NULL DEFAULT FALSE;
