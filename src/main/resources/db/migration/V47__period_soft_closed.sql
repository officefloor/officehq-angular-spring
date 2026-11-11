-- Whether a period is soft-closed: its due-total and each person's due-total for it are fixed (closed_total and
-- period_person_total) as for a close, yet claims dated in it can still change. A period is never both closed and
-- soft-closed.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN DEFAULT FALSE NOT NULL;
-- Whether a claim was added while the period its date falls in was soft-closed.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
