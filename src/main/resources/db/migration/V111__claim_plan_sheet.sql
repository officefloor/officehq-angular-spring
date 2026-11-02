-- The descendant sheet a plan generates its claims on, for the person of the same name there; none: the
-- plan's own sheet and person.
ALTER TABLE claim_plan ADD COLUMN target_sheet VARCHAR(100);
