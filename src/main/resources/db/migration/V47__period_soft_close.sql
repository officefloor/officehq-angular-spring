-- A soft-closed period: its due-total and each person's figure for it are fixed (closed_total and
-- period_person_total) as for a close, but claims dated inside it can still be added and changed. A soft-closed
-- period is never also closed.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN DEFAULT FALSE NOT NULL;

-- A late entry: a claim added while the period its date is in was soft-closed.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
