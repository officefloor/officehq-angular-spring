-- The day the client was taken on; existing clients count as taken on the day this was added.
ALTER TABLE client ADD COLUMN created_date DATE DEFAULT CURRENT_DATE NOT NULL;
