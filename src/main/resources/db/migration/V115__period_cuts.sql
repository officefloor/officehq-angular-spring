-- What a closed or soft-closed period's pool cuts are worked out from, fixed when it was closed: the
-- sheet's today then, its pot, people and pools, as JSON. Null while the period is open.
ALTER TABLE period ADD COLUMN cuts CLOB;
