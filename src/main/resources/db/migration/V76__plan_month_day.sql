-- A plan may instead run monthly on a day of the month (1 to 31): then every_days is empty and month_day holds the
-- day; a day beyond a month's length falls on that month's last day.
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN month_day INT;
