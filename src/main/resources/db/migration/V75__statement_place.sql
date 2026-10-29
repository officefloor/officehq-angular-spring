-- A person's statements are in the order taken (by id), except those that came with another person merged into
-- them: those stand before the person's own, so the person's last statement stays theirs. The place a statement
-- stands at in its person's order; null for its own id.
ALTER TABLE statement ADD COLUMN place BIGINT;
