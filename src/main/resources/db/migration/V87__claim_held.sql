-- Whether an OPEN or PENDING claim is held: while held and unbound by a statement, it counts in no live
-- total, as a held person's claims do. Each hold and release keeps the sheet's today it was made on, oldest
-- first, so a statement taken as of a date sees the claim held as it was then.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE claim_hold (
    claim_id BIGINT  NOT NULL REFERENCES claim (id),
    position INT     NOT NULL,
    on_date  DATE    NOT NULL,
    held     BOOLEAN NOT NULL,
    PRIMARY KEY (claim_id, position)
);
