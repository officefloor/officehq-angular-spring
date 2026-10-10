-- A job can have a start date and an end date; either may be unset. The end may not be before the start.
ALTER TABLE project ADD COLUMN start_date DATE;
ALTER TABLE project ADD COLUMN end_date DATE;
ALTER TABLE project ADD CONSTRAINT project_dates_ordered CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date);
