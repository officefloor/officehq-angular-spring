-- A plan may instead run monthly on a day of the month (1 to 31): its dates are that day in every month
-- from the start's month, a day beyond the month's length falling on its last day, dates before the start
-- left out. A plan has exactly one of every_days and day_of_month.
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN day_of_month INT;
