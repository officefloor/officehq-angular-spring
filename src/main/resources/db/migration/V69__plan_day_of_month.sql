-- A plan may instead run monthly on a day of the month (1 to 31): its plan dates are that day in every
-- month from the start's month, a day beyond the month's length falling on its last day, dates before
-- the start left out. Such a plan has a day_of_month and no every_days; any other has every_days only.
ALTER TABLE person_plan ALTER COLUMN every_days SET NULL;
ALTER TABLE person_plan ADD COLUMN day_of_month INT;
