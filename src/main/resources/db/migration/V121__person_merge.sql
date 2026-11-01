-- A merge of one person of a sheet into another, kept so the steward who made it can undo it on the same sheet's
-- today: who was merged into whom, by whom and on which today, and what the merged person was (their name, grades,
-- terms, tags, retirements past, flag, approver and own aliases) with the claims that moved from them. Ids rise in
-- the order merges are made; an undone merge is kept, marked undone.
CREATE TABLE person_merge (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    from_id     BIGINT       NOT NULL,
    into_id     BIGINT       NOT NULL,
    user_name   VARCHAR(100) NOT NULL,
    made_on     DATE,
    undone      BOOLEAN      NOT NULL DEFAULT FALSE,
    name        VARCHAR(200) NOT NULL,
    terms       INT,
    tags        VARCHAR(1000),
    flag_reason VARCHAR(200),
    flag_until  DATE,
    approver    VARCHAR(100)
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);
CREATE TABLE person_merge_grade (
    merge_id  BIGINT     NOT NULL REFERENCES person_merge (id),
    from_date DATE,
    grade     VARCHAR(3) NOT NULL
);
CREATE TABLE person_merge_terms (
    merge_id  BIGINT NOT NULL REFERENCES person_merge (id),
    from_date DATE   NOT NULL,
    terms     INT
);
CREATE TABLE person_merge_past_retirement (
    merge_id  BIGINT NOT NULL REFERENCES person_merge (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL
);
CREATE TABLE person_merge_alias (
    merge_id BIGINT       NOT NULL REFERENCES person_merge (id),
    alias    VARCHAR(200) NOT NULL
);
CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL
);
