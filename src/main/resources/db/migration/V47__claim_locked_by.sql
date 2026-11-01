-- The user whose open editor locks the claim for everyone else; none while no editor holds it.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
