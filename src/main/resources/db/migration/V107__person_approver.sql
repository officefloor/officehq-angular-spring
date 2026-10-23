-- A user name that may approve or reject the person's PENDING claims as a steward may; null when none is set.
ALTER TABLE person ADD COLUMN approver VARCHAR(200);
