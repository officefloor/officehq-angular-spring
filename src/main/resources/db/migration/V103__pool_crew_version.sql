-- Pools and crews are kept in the history (entity_version) so their cuts can be read back as of a date; a
-- version moved on by each save makes a change to their members a change of the pool or crew itself.
ALTER TABLE pool ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE crew ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
