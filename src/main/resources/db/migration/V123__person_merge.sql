-- A merged person is kept aside rather than removed, so the merge can be undone: merged_away hides them from the
-- sheet, with their name freed. Each merge is kept with who made it, the sheet's today then, and what it moved: the
-- claims (each with its place in its person's list before), the aliases, and the alias their name became.
ALTER TABLE person ADD COLUMN merged_away BOOLEAN DEFAULT FALSE NOT NULL;
CREATE TABLE person_merge (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    from_id       BIGINT       NOT NULL,
    into_id       BIGINT       NOT NULL,
    merged_by     VARCHAR(100) NOT NULL,
    merged_on     DATE         NOT NULL,
    name_alias_id BIGINT
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);
CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id) ON DELETE CASCADE,
    claim_id BIGINT NOT NULL,
    listed   BIGINT NOT NULL,
    PRIMARY KEY (merge_id, claim_id)
);
CREATE TABLE person_merge_alias (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id) ON DELETE CASCADE,
    alias_id BIGINT NOT NULL,
    PRIMARY KEY (merge_id, alias_id)
);
