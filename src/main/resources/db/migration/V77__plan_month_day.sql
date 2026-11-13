-- A plan may instead run monthly on a day of the month (1 to 31): such a plan keeps that day and no interval.
-- Existing plans keep their interval and have no day.
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN month_day INT;
