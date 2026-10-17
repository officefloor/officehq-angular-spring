-- The sheet's policy date: a claim dated before it rounds half up, on or after it half to even; null for the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
