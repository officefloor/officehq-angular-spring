-- Each time a plan was paused: from paused_on (the sheet's today when paused) up to, not including,
-- resumed_on (the sheet's today when resumed; null while it is still paused). A plan date in that
-- span is skipped. A plan has at most one pause still open.
CREATE TABLE plan_pause (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    paused_on DATE NOT NULL,
    resumed_on DATE
);
CREATE INDEX plan_pause_plan ON plan_pause (plan_id);
