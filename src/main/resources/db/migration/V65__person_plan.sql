-- A person's plans: an amount, a start date, every N days (at least 1) and an end date. Generating
-- makes a DRAFT claim for each plan date (the start, then every N days) up to the end and the
-- sheet's today; person_plan_date keeps each date a plan has made its claim for, so it is made once.
CREATE TABLE person_plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    amount     DECIMAL(12, 2) NOT NULL,
    start_date DATE           NOT NULL,
    every_days INT            NOT NULL,
    end_date   DATE           NOT NULL
);
CREATE INDEX person_plan_sheet ON person_plan (sheet_id);
CREATE TABLE person_plan_date (
    plan_id   BIGINT NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    plan_date DATE   NOT NULL,
    PRIMARY KEY (plan_id, plan_date)
);
