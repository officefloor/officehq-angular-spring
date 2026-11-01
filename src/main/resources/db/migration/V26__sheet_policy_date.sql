-- The sheet's policy date: a claim dated before it rounds half up, one on or after it half to even.
-- Empty: the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
