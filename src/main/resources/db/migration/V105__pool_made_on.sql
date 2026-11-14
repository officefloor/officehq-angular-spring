-- The sheet's today when each pool, crew and membership was added, so a pool's cuts can be read back
-- as they stood at the end of a past day; null for one added before this was kept (it always stood).
ALTER TABLE pool ADD COLUMN made_on DATE;
ALTER TABLE pool_member ADD COLUMN made_on DATE;
ALTER TABLE crew ADD COLUMN made_on DATE;
ALTER TABLE crew_member ADD COLUMN made_on DATE;
