-- A settle batch: a bulk settle that settles at least one claim (one that goes to PENDING counts), numbered 1, 2,
-- ... on its sheet in the order they happen. The sheet keeps the last batch number it gave out (0 before the first);
-- each claim settled by a batch keeps its number, none when it was settled one at a time.
ALTER TABLE sheet ADD COLUMN last_settle_batch INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN settle_batch INT;
