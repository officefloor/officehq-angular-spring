-- The changes of a plan's amount, each on the sheet's today it was made. The plan's amount is its latest: the claims
-- generated from then on are of it, those already generated keep theirs. Listed oldest first.
CREATE TABLE plan_change (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    plan_id     BIGINT         NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    changed_on  DATE           NOT NULL,
    amount      DECIMAL(12, 2) NOT NULL
);
CREATE INDEX plan_change_sheet ON plan_change (sheet_id, plan_id, id);
