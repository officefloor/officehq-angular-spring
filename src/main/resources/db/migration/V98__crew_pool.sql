-- A crew is also a pool: a pool row of the sheet under the crew's name, marked with its crew. Its
-- members are not stored: they are the crew's lead and members, at equal shares. The pool's name
-- being unique on the sheet, a pool and a crew never share a name.
ALTER TABLE pool ADD COLUMN crew_id BIGINT REFERENCES crew (id);
ALTER TABLE pool ADD CONSTRAINT pool_crew_unique UNIQUE (crew_id);
INSERT INTO pool (sheet_id, name, crew_id)
SELECT c.sheet_id, c.name, c.id FROM crew c
WHERE NOT EXISTS (SELECT 1 FROM pool p WHERE p.sheet_id = c.sheet_id AND p.name = c.name)
ORDER BY c.id;
