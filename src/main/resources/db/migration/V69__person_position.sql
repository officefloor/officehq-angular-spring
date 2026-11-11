-- People have a position in the list: listed by it, then by id. People already kept keep the order they were added.
ALTER TABLE person ADD COLUMN position BIGINT DEFAULT 0 NOT NULL;
UPDATE person SET position = id;
CREATE INDEX person_sheet_position ON person (sheet_id, position, id);
