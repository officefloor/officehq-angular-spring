-- A person's place in the list, lowest first. A person added carries none until they are moved, and
-- takes their id's place, which is after everyone added before them; a move swaps two people's places.
ALTER TABLE person ADD COLUMN position BIGINT;
UPDATE person SET position = id;
