-- A person's place in the list: people are listed by it, ascending. A person added goes to the end;
-- moving a person up or down swaps their position with their neighbour's.
CREATE SEQUENCE person_position_seq;
ALTER TABLE person ADD COLUMN list_position BIGINT DEFAULT NEXT VALUE FOR person_position_seq NOT NULL;
CREATE INDEX person_sheet_position ON person (sheet_id, list_position);
