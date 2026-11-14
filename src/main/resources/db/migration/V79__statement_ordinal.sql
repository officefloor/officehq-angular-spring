-- Where a statement falls among its person's statements: a person's last statement is the one with the
-- highest ordinal, or id when it has none. A statement moved to another person by a merge, when that
-- person has statements of their own, is given an ordinal before theirs, so their last stays theirs.
ALTER TABLE statement ADD COLUMN ordinal BIGINT;
