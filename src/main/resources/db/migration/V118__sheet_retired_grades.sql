-- The grades retired on a sheet, comma separated in A, B, C order; null for none.
ALTER TABLE sheet ADD COLUMN retired_grades VARCHAR(20);
