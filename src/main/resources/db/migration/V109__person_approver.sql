-- The user who may approve or reject a person's PENDING claims as a steward may; null for none.
ALTER TABLE person ADD COLUMN approver VARCHAR(200);
