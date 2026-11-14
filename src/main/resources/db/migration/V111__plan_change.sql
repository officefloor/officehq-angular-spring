-- Each change of a plan's amount, with the sheet's today when it was made. The plan's own amount is
-- the one it was added with; the claims it generates take the amount of its latest change, if any.
CREATE TABLE plan_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    changed_on DATE NOT NULL,
    amount DECIMAL(12, 2) NOT NULL
);
CREATE INDEX plan_change_plan ON plan_change (plan_id);
