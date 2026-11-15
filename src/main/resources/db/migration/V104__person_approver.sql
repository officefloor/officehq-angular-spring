-- A person's approver: a user name who may approve or reject the person's PENDING claims as a
-- steward may. NULL when they have none.
ALTER TABLE person ADD COLUMN approver VARCHAR(100);
