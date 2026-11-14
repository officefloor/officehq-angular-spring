-- The sheet's policy date: a claim dated before it rounds half up, one on or after it half to
-- even; null when blank, when the sheet's today is the policy date.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
