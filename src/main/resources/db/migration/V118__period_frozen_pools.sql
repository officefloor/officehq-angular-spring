-- The pools, pot and people as they stood when the period was closed or soft-closed (JSON), so each
-- pool member's cut for the period stays as it was then. Null while the period is open.
ALTER TABLE period ADD COLUMN frozen_pools CLOB;
