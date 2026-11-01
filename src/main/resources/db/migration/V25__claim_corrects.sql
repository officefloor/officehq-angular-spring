-- A correction (a claim with a negative amount) names, by number, the SETTLED claim of the same
-- person that it corrects. Empty for any other claim.
ALTER TABLE claim ADD COLUMN corrects BIGINT;
