-- A period can be closed: its due-total is then fixed at the figure it had when it was closed
-- (later rate changes do not move it), and no claim dated inside it can be added, edited,
-- opened, settled or voided.
ALTER TABLE period ADD COLUMN closed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE period ADD COLUMN closed_total DECIMAL(15, 2);
