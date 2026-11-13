-- A period can be soft-closed: its due-total and each person's figure for it are fixed as a close
-- fixes them (closed_total and period_person_total), but claims dated in it can still change. A
-- soft-closed period is not closed; closing it fixes the figures afresh, reopening makes them live.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN DEFAULT FALSE NOT NULL;
-- A claim added while the period its date falls in was soft-closed is a late entry.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
