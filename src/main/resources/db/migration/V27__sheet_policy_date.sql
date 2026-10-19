-- A sheet's policy date: a claim dated before it rounds half up, one dated on or after it half to even. None means
-- the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
