-- The plans of a person: an amount, a start date, every N days (at least 1) and an end date. Generating
-- makes a DRAFT claim of the plan's amount for each plan date (the start, then every N days) up to the
-- end and the sheet's today that has none yet; each date a plan has made its claim for is kept in
-- plan_claim, with the claim made, so it is made once.
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

CREATE TABLE plan_claim (
    plan_id    BIGINT NOT NULL REFERENCES person_plan (id) ON DELETE CASCADE,
    claim_date DATE   NOT NULL,
    claim_id   BIGINT NOT NULL,
    PRIMARY KEY (plan_id, claim_date)
);
