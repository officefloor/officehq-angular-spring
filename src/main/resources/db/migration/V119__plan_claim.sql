-- The claims a plan has made, so a change of its amount can reach those still DRAFT.
CREATE TABLE plan_claim (
    plan_id  BIGINT NOT NULL REFERENCES plan (id),
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (plan_id, claim_id)
);
