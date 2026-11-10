-- A period can be soft-closed: its due-total and each person's due-total for it are fixed at that
-- moment (in closed_due and period_person_due, as for a closed period), but claims dated inside it
-- can still be added, edited, opened, settled and voided. Closing or reopening it clears the flag.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN DEFAULT FALSE NOT NULL;
-- A claim added while the period its date falls in was soft-closed is a late entry.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
