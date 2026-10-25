-- The merges made into people, so the last into a person can be undone: the sheet's today it was made
-- on and by whom, the merged person as they stood (to restore them under the same id), the claims that
-- moved from them and their own aliases; undone once it has been undone.
CREATE TABLE person_merge (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100)  NOT NULL,
    into_id      BIGINT        NOT NULL,
    from_id      BIGINT        NOT NULL,
    from_name    VARCHAR(200)  NOT NULL,
    merged_by    VARCHAR(100)  NOT NULL,
    merged_on    DATE          NOT NULL,
    person_state CLOB          NOT NULL,
    undone       BOOLEAN       DEFAULT FALSE NOT NULL
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);
CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL
);
CREATE INDEX person_merge_claim_merge ON person_merge_claim (merge_id);
CREATE TABLE person_merge_alias (
    merge_id BIGINT       NOT NULL REFERENCES person_merge (id),
    alias    VARCHAR(200) NOT NULL
);
CREATE INDEX person_merge_alias_merge ON person_merge_alias (merge_id);
