-- The instalment of a plan the client skipped (it pays only its interest, and the instalments after
-- it are worked out again); null when none was.
ALTER TABLE invoice ADD COLUMN skipped_instalment INT;
