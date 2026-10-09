-- A job may carry a short description of the work, given when it is added. Jobs added before
-- descriptions existed, or added without one, have none.
ALTER TABLE project ADD COLUMN description VARCHAR(500);
