-- The claims a plan has made, so a change of its amount can reach those still in draft. A claim made before this was
-- kept is not known to its plan and keeps its amount.
CREATE TABLE plan_made_claim (
    plan_id  BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (plan_id, claim_id)
);
