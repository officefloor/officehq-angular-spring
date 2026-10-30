-- A claim's comments, oldest first: each with the user who wrote it and the sheet's today when it was
-- written. A claim already there starts with none.
CREATE TABLE claim_comment (
    claim_id BIGINT NOT NULL REFERENCES claim(id),
    seq INT NOT NULL,
    by_user VARCHAR(200) NOT NULL,
    on_date DATE NOT NULL,
    text VARCHAR(1000) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
