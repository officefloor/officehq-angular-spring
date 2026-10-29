-- The user who may approve or reject the person's PENDING claims as a steward may; null: none.
ALTER TABLE person ADD COLUMN approver VARCHAR(100);
