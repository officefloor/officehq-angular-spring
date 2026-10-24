-- A plan runs either every N days (every_days) or monthly on a day of the month, 1 to 31 (day_of_month):
-- exactly one of the two is set. A monthly plan's dates are that day in every month from the start's
-- month, a day beyond the month's length falling on its last day, dates before the start left out.
ALTER TABLE person_plan ALTER COLUMN every_days DROP NOT NULL;
ALTER TABLE person_plan ADD COLUMN day_of_month INT;
