-- A soft-closed period: its figures are fixed as for a closed one, but claims dated inside it can still change.
ALTER TABLE period ADD COLUMN soft_closed BOOLEAN NOT NULL DEFAULT FALSE;
-- Whether the claim was added while its period was soft-closed.
ALTER TABLE claim ADD COLUMN late_entry BOOLEAN NOT NULL DEFAULT FALSE;
