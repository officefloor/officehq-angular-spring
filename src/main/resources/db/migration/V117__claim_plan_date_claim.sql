-- The claim a plan made for a date, so a change of the plan's amount reaches those still DRAFT. Null for
-- dates made before it was kept, and for a claim made on a descendant sheet that no longer exists.
ALTER TABLE claim_plan_date ADD COLUMN claim_id BIGINT;
