-- A plan runs either every so many days (every_days) or monthly on a day of the month (month_day,
-- 1 to 31: that day in every month from the start's month, a day beyond the month's length falling
-- on its last day, dates before the start left out). Exactly one of the two is set.
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN month_day INT;
