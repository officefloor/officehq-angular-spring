-- A soft-closed period has its due-totals fixed like a closed one, but claims dated in it can still change.
ALTER TABLE period ADD COLUMN soft BOOLEAN DEFAULT FALSE NOT NULL;
-- Whether the claim was added while the period it is dated in was soft-closed.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN DEFAULT FALSE NOT NULL;
