-- A statement carried to a person by a merge or a transfer from another person: it counts among the
-- person's statements, but their last statement stays their own (a carried one only when they have none).
ALTER TABLE statement ADD COLUMN carried BOOLEAN DEFAULT FALSE NOT NULL;
