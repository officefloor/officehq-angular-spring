-- Where a statement stands among its person's statements, their last being the one placed last. A
-- statement taken is placed by its id; those that move with a merge or a transfer are placed before the
-- receiving person's own.
ALTER TABLE statement ADD COLUMN list_position BIGINT;
UPDATE statement SET list_position = id;
