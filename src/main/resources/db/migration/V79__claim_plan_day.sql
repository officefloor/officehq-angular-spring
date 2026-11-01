-- A plan may instead run monthly on a day of the month (1 to 31): then day_of_month is set and
-- every_days is none. Existing plans keep their every so many days.
ALTER TABLE claim_plan ALTER COLUMN every_days SET NULL;
ALTER TABLE claim_plan ADD COLUMN day_of_month INT;
