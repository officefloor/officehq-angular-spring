-- Whether the claim was deferred while a statement bound it: such a deferral takes effect only once
-- the binding ends (the statement is cancelled, or the next statement leaves the claim unbound);
-- until then the claim stays in the totals, counts and can be settled.
ALTER TABLE claim ADD COLUMN deferred_bound BOOLEAN NOT NULL DEFAULT FALSE;
