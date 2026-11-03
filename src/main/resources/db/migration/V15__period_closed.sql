-- A period can be closed. Closing fixes its due-total at that moment (the due of the claims that
-- count whose date falls in it); later rate changes do not alter it. None while the period is open.
-- No claim dated inside a closed period can be added, edited or moved to another status.
ALTER TABLE period ADD COLUMN closed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE period ADD COLUMN closed_due DECIMAL(14, 2);
