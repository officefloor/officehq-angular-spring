-- The sheet's today when a share of a claim was given: the claim as it stood at the end of an earlier
-- day shows only the shares given by then. Null for a share given before this was kept (always shown).
ALTER TABLE claim_share ADD COLUMN on_day DATE;
