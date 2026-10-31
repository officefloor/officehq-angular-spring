-- A snapshot line keeps the today the sheet moved to when it was written: as of a date before that, the day it was
-- written for had not been left yet. A line kept before has none (it shows as of any date after its day).
ALTER TABLE person_snapshot ADD COLUMN written_on DATE;
