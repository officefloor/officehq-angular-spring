-- The descendant sheet a plan makes its claims on, for the person of the same name there; null for a
-- plan that makes them on its own sheet for its own person.
ALTER TABLE plan ADD COLUMN target_sheet_id VARCHAR(200);
