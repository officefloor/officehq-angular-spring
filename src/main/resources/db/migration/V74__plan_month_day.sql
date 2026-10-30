-- A plan runs either every N days or monthly on a day of the month (1 to 31): exactly one of the two is set.
ALTER TABLE sheet_plan ALTER COLUMN every_days SET NULL;
ALTER TABLE sheet_plan ADD COLUMN month_day INT;
