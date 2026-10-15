-- A person's kind: A, B or C; absent until given.
ALTER TABLE person ADD COLUMN kind VARCHAR(1);
ALTER TABLE person ADD CONSTRAINT person_kind_check CHECK (kind IN ('A', 'B', 'C'));
