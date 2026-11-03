-- The sheet's today when a pool, a pool membership or a crew membership was added (null: before
-- this was kept, counting as always there): the sheet as it stood at the end of an earlier day has
-- none of them.
ALTER TABLE pool ADD COLUMN added_on DATE;
ALTER TABLE pool_member ADD COLUMN added_on DATE;
ALTER TABLE crew_member ADD COLUMN added_on DATE;
