-- The grades retired on the sheet, comma separated; null: none. A retired grade cannot be given to anyone from then on. Not dated.
ALTER TABLE sheet ADD COLUMN retired_grades VARCHAR(20);
