-- A plan runs either every so many days (every_days) or monthly on a day of the month (day_of_month,
-- 1 to 31): exactly one of the two is set.
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN day_of_month INT;
