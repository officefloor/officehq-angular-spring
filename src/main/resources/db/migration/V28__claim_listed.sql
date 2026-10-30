-- A claim's place in its person's list, newest first: taken when the claim is added, and taken again
-- when the claim is reassigned to another person, so it lists as that person's newest claim.
CREATE SEQUENCE claim_listed_seq;
ALTER TABLE claim ADD COLUMN listed BIGINT DEFAULT NEXT VALUE FOR claim_listed_seq NOT NULL;
