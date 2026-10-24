-- The sheet's policy date: a claim dated before it rounds its live figures half up, one dated on or
-- after it half to even (a correction by the date of the claim it corrects). Null means the sheet's today.
ALTER TABLE sheet ADD COLUMN policy_date DATE;
