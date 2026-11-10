-- A claim's comments, oldest first: each with the user who wrote it and the sheet's today then.
CREATE TABLE claim_comment (
    claim_id     BIGINT        NOT NULL REFERENCES claim (id),
    seq          INTEGER       NOT NULL,
    by_user      VARCHAR(100)  NOT NULL,
    comment_date DATE          NOT NULL,
    text         VARCHAR(1000) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
