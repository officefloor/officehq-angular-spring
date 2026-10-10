-- A client can have an account manager: the person in the office who looks after them. Existing
-- clients have none recorded.
ALTER TABLE client ADD COLUMN account_manager VARCHAR(255);
