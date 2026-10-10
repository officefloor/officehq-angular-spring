-- A job can be put into a category (e.g. "Web"); unset when it has none.
ALTER TABLE project ADD COLUMN category VARCHAR(50);
