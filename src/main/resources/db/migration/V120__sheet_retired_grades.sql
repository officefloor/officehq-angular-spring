-- The grades retired on a sheet, comma separated: none can be chosen for a person from then on.
ALTER TABLE sheet ADD COLUMN retired_grades VARCHAR(20);
