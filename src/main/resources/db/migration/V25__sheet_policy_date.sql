-- The sheet's policy date: a claim dated before it rounds half up, one dated on or after it half to
-- even. None (null) on a new sheet: the policy date is then the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
