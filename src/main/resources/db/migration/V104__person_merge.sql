-- A merge can be undone, so the person merged leaves the sheet without being deleted: their row is kept
-- with no sheet (on none, found by no list or name) until an undo puts them back. Each merge is recorded:
-- who made it on which of the sheet's todays, the person merged and the one merged into, the claims that
-- moved and the names (the merged name and aliases) the other was given as aliases. A merge undone is kept,
-- marked undone.
ALTER TABLE person ALTER COLUMN sheet_id SET NULL;

CREATE TABLE person_merge (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL,
    into_id   BIGINT       NOT NULL,
    made_by   VARCHAR(200) NOT NULL,
    made_on   DATE         NOT NULL,
    undone    BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);

CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL
);

CREATE TABLE person_merge_alias (
    merge_id BIGINT       NOT NULL REFERENCES person_merge (id),
    alias    VARCHAR(200) NOT NULL
);
