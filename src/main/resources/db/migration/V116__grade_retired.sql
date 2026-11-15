-- The sheet's retired grades, comma separated in the order retired: a retired grade cannot be
-- chosen for a new person or in an edit; the people already on it keep it. Null when none.
ALTER TABLE sheet ADD COLUMN retired_grades VARCHAR(20);
