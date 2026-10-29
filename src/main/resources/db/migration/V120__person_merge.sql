-- Every merge of one person into another on a sheet, made by the user while the sheet's today was made_on; undone once
-- the merge has been undone. The merged person is kept as they stood (with their grades, holds, terms, past
-- retirements and own aliases) so an undo can bring them back, with the claims that came from them and the aliases the
-- merge gave the other.
CREATE TABLE person_merge (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL,
    from_id   BIGINT       NOT NULL,
    into_id   BIGINT       NOT NULL,
    made_by   VARCHAR(100) NOT NULL,
    made_on   DATE,
    undone    BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX person_merge_into ON person_merge (sheet_id, into_id, id);

CREATE TABLE person_merged (
    merge_id      BIGINT       NOT NULL REFERENCES person_merge (id),
    id            BIGINT       NOT NULL,
    sheet_id      VARCHAR(100) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    version       BIGINT       NOT NULL,
    retired_from  DATE,
    terms         INT,
    added_on      DATE,
    retired_on    DATE,
    contact       VARCHAR(200),
    origin_sheet  VARCHAR(100),
    tags          VARCHAR(1000),
    arrived_on    DATE,
    arrived_by    VARCHAR(100),
    flag_reason   VARCHAR(200),
    flag_until    DATE,
    approver      VARCHAR(100)
);
CREATE TABLE person_merged_grade (
    merge_id  BIGINT     NOT NULL REFERENCES person_merge (id),
    grade     VARCHAR(3) NOT NULL,
    from_date DATE,
    made_on   DATE
);
CREATE TABLE person_merged_hold (
    merge_id  BIGINT NOT NULL REFERENCES person_merge (id),
    from_date DATE   NOT NULL,
    to_date   DATE,
    made_on   DATE
);
CREATE TABLE person_merged_terms (
    merge_id  BIGINT NOT NULL REFERENCES person_merge (id),
    from_date DATE   NOT NULL,
    terms     INT,
    made_on   DATE
);
CREATE TABLE person_merged_retirement (
    merge_id   BIGINT NOT NULL REFERENCES person_merge (id),
    from_date  DATE   NOT NULL,
    to_date    DATE   NOT NULL,
    retired_on DATE,
    made_on    DATE
);
-- the merged person's own aliases
CREATE TABLE person_merged_alias (
    merge_id BIGINT       NOT NULL REFERENCES person_merge (id),
    alias    VARCHAR(200) NOT NULL
);
-- the aliases the merge gave the person merged into
CREATE TABLE person_merge_given_alias (
    merge_id BIGINT       NOT NULL REFERENCES person_merge (id),
    alias    VARCHAR(200) NOT NULL
);
-- the claims that came from the merged person
CREATE TABLE person_merge_claim (
    merge_id BIGINT NOT NULL REFERENCES person_merge (id),
    claim_id BIGINT NOT NULL
);
