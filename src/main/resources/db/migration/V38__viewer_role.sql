-- A third role, viewer: sees everything and can change nothing.
ALTER TABLE sheet_role DROP CONSTRAINT sheet_role_known;
ALTER TABLE sheet_role ADD CONSTRAINT sheet_role_known CHECK (role IN ('clerk', 'steward', 'viewer'));
