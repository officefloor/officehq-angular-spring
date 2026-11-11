-- A plan may instead run monthly on a day of the month (1 to 31): its dates are that day in every month from the
-- start's month (a day beyond the month's length falling on its last day), those before the start left out. A plan
-- keeps either every_days or month_day, never both.
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN month_day INT;
