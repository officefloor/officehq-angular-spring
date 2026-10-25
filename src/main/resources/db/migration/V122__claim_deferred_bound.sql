-- Whether the claim's deferral was made while a statement bound it: such a claim may be settled while
-- bound, and once the binding ends the deferral takes effect on it SETTLED too.
ALTER TABLE claim ADD COLUMN deferred_bound BOOLEAN NOT NULL DEFAULT FALSE;
