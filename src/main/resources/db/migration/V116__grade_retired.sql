-- The grades retired on the sheet, comma separated in the order retired; null (blank) for none. A retired grade
-- cannot be given to a new person nor chosen in an edit; people already on it keep it. Not dated.
ALTER TABLE sheet ADD COLUMN retired_grades VARCHAR(20);
