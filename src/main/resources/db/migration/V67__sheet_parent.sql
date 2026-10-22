-- The sheet's parent: a sheet whose rates apply where this sheet has none in force. Null means none.
ALTER TABLE sheet ADD COLUMN parent_id VARCHAR(64);
