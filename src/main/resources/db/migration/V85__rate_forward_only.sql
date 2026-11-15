-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to
-- claims added after it was saved. Such a rate (version) keeps the highest claim id there was when
-- it was saved; a claim applies it only when it was added after that (by its own id, or for a copy
-- in a clone of a sheet the id of the claim it copies). Null: the rate applies to every claim.
ALTER TABLE rate ADD COLUMN after_claim BIGINT;
-- The claim a copy in a clone was copied from was added as; null for a claim added on its sheet
-- (it was added as its own id).
ALTER TABLE claim ADD COLUMN added BIGINT;
-- The versions a claim added before a forward-only rate's newest version falls back to, newest
-- first: each forward-only one, up to and including the first that applies to every claim.
CREATE TABLE rate_earlier (
    rate_id     BIGINT        NOT NULL REFERENCES rate (id),
    position    INTEGER       NOT NULL,
    pct         DECIMAL(5, 2) NOT NULL,
    to_date     DATE,
    after_claim BIGINT,
    PRIMARY KEY (rate_id, position)
);
