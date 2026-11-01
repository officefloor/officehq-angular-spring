-- A period can be closed. Closing fixes its due-total at that moment: the dues of the claims that
-- count whose date falls in it. No claim dated inside a closed period can then be added, edited,
-- opened, settled or voided. The fixed due-total is empty while the period is open.
ALTER TABLE period ADD COLUMN closed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE period ADD COLUMN closed_due_total DECIMAL(14, 2);
