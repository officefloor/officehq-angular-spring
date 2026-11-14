-- A steward's merge of a person into another, kept so the last merge into a person can be undone on
-- the sheet's today it was made, by the steward who made it: the claims that moved and the aliases the
-- merged person brought (their own, and the one made of their name). Undoing it brings the merged
-- person back with those claims and aliases, and marks it undone.
CREATE TABLE person_merge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    from_id BIGINT NOT NULL REFERENCES person (id),
    into_id BIGINT NOT NULL REFERENCES person (id),
    made_by VARCHAR(200) NOT NULL,
    made_on DATE NOT NULL,
    name_alias_id BIGINT,
    undone BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX person_merge_into ON person_merge (into_id, id);
CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL
);
CREATE TABLE person_merge_alias (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    alias_id BIGINT NOT NULL
);
