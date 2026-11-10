-- A plan may instead run monthly on a day of the month (1 to 31): its plan dates are that day in
-- every month from the start's month (a day beyond the month's length falls on its last day), dates
-- before the start left out. A plan has either every_days or day_of_month, never both.
ALTER TABLE plan ALTER COLUMN every_days DROP NOT NULL;
ALTER TABLE plan ADD COLUMN day_of_month INT CHECK (day_of_month BETWEEN 1 AND 31);
ALTER TABLE plan ADD CONSTRAINT plan_schedule CHECK ((every_days IS NULL) <> (day_of_month IS NULL));
