-- Whether a period is soft-closed: its due-total and each person's are fixed (in closed_total and
-- period_person_total) as for a closed period, but claims dated in it can still change.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN NOT NULL DEFAULT FALSE;
-- Whether the claim was added while its period was soft-closed.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN NOT NULL DEFAULT FALSE;
