-- A person's approver: a user name, trimmed, who may approve or reject the person's pending claims
-- as a steward may; null when they have none.
ALTER TABLE person ADD COLUMN approver VARCHAR(100);
