-- Where the statement stands among its person's statements: a person's statements are in the order of seq, then id,
-- and the last one is the person's last statement. Statements that follow a person merged into another are placed
-- before that person's own, so the receiving person's last statement stays theirs.
ALTER TABLE statement ADD COLUMN seq BIGINT;
UPDATE statement SET seq = id;
ALTER TABLE statement ALTER COLUMN seq SET NOT NULL;
CREATE INDEX statement_person_seq ON statement (person_id, seq, id);
