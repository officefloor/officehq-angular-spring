-- The least live due a claim on the sheet has; a sheet starts at 1.00.
ALTER TABLE sheet ADD COLUMN due_floor DECIMAL(14, 2) DEFAULT 1.00 NOT NULL;
