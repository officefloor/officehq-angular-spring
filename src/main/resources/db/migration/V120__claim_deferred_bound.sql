-- Whether the claim was deferred while a statement bound it: the deferral takes effect only once
-- the binding ends, and then whatever the claim's status (it may have been settled while bound).
ALTER TABLE claim ADD COLUMN deferred_bound BOOLEAN NOT NULL DEFAULT FALSE;
