-- The pools as they stood when a period was closed or soft-closed (JSON: the pools, crews, people and pot, with the
-- sheet's today then), so each member's cut for the period stays as it was then; null while the period is open.
ALTER TABLE period ADD COLUMN fixed_pools VARCHAR(1000000);
