-- Each claim's comments, oldest first: the text, who wrote it and the sheet's today when it was written.
CREATE TABLE claim_comment (
    claim_id     BIGINT        NOT NULL REFERENCES claim (id),
    seq          INT           NOT NULL,
    by_user      VARCHAR(100)  NOT NULL,
    comment_date DATE          NOT NULL,
    comment_text VARCHAR(500)  NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
