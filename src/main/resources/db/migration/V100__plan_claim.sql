-- The claims a plan has generated, each by its id (on the plan's sheet, or the descendant sheet it names),
-- so a change of the plan's amount can reach those still DRAFT. Claims generated before this was kept
-- are not listed.
CREATE TABLE person_plan_claim (
    plan_id  BIGINT NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (plan_id, claim_id)
);
