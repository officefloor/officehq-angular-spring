-- People have a position in their sheet's list: the list, its rollup lines and every action taken in
-- list order follow it. People added before this keep the order they were added in.
ALTER TABLE person ADD COLUMN list_position INT;
UPDATE person p SET list_position = (SELECT COUNT(*) FROM person q WHERE q.sheet_id = p.sheet_id AND q.id <= p.id);
ALTER TABLE person ALTER COLUMN list_position SET NOT NULL;
