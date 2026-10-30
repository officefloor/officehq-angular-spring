-- A fourth role, restricted: lists only the people of the crews they lead or belong to, and themselves.
ALTER TABLE sheet_role DROP CONSTRAINT sheet_role_known;
ALTER TABLE sheet_role ADD CONSTRAINT sheet_role_known CHECK (role IN ('clerk', 'steward', 'viewer', 'restricted'));
