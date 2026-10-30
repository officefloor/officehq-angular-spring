-- The sheet's policy date: a claim dated before it rounds half up, one dated on or after it half to
-- even; null (blank) means the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
-- The date of the claim a correction corrects, which its rounding goes by; null for any other claim.
ALTER TABLE claim ADD COLUMN corrects_date DATE;
