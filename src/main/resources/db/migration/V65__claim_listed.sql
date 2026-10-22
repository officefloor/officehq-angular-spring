-- The place a claim is listed at: claims are listed newest first by it. A claim takes the next value
-- when it is added, and again when it moves to another person by a merge, so the moved claims are
-- listed after (newer than) that person's own, in the order they were added.
CREATE SEQUENCE claim_listed_seq START WITH 1;
ALTER TABLE claim ADD COLUMN listed BIGINT DEFAULT NEXT VALUE FOR claim_listed_seq NOT NULL;
