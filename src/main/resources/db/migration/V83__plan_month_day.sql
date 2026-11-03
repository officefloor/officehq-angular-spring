-- A plan may instead repeat monthly on a day of the month (1 to 31): exactly one of every_days and
-- month_day is set.
ALTER TABLE plan DROP CONSTRAINT plan_every;
ALTER TABLE plan ALTER COLUMN every_days SET NULL;
ALTER TABLE plan ADD COLUMN month_day INT;
ALTER TABLE plan ADD CONSTRAINT plan_repeat CHECK (
    (every_days IS NOT NULL AND every_days >= 1 AND month_day IS NULL)
    OR (every_days IS NULL AND month_day BETWEEN 1 AND 31));
