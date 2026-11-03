-- Whether a statement came to its person with a merge or a transfer (it was another person's). A
-- person's last statement is their own last one, or, having none, the last one carried to them.
ALTER TABLE statement ADD COLUMN carried BOOLEAN NOT NULL DEFAULT FALSE;
