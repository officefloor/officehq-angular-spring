-- The user who may approve or reject a person's PENDING claims as a steward may; null when they have
-- none.
ALTER TABLE person ADD COLUMN approver VARCHAR(100);
