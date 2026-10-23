-- A crew is also a pool: a pool row under the crew's name, naming the crew. Its members are not stored:
-- they are the crew's lead and members, at equal shares. A pool and a crew never share a name on a sheet,
-- which the pool's unique name index keeps. A crew from before whose name a pool already has stays
-- without a pool of its own.
ALTER TABLE pool ADD COLUMN crew_id BIGINT REFERENCES crew (id);
CREATE UNIQUE INDEX pool_crew ON pool (crew_id);
INSERT INTO pool (sheet_id, name, crew_id)
    SELECT c.sheet_id, c.name, c.id FROM crew c
    WHERE NOT EXISTS (SELECT 1 FROM pool p WHERE p.sheet_id = c.sheet_id AND p.name = c.name)
    ORDER BY c.id;
