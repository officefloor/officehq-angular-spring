-- Each claim's comments: one line per comment, in the order written, with the user who wrote it and the
-- sheet's today when it was written. Existing claims start with none.
CREATE TABLE claim_comment (
    claim_id BIGINT        NOT NULL REFERENCES claim (id),
    seq      INT           NOT NULL,
    by_user  VARCHAR(200)  NOT NULL,
    on_date  DATE          NOT NULL,
    comment_text VARCHAR(1000) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
