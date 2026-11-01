-- A person's plans: an amount from a start date every so many days up to an end date. Generating
-- makes a DRAFT claim for each plan date reached that the plan has made none for yet; each date a
-- plan has made a claim for is kept, once.
CREATE TABLE claim_plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(14, 2) NOT NULL,
    start_on   DATE           NOT NULL,
    every_days INT            NOT NULL,
    end_on     DATE           NOT NULL
);
CREATE INDEX claim_plan_sheet ON claim_plan (sheet_id, id);
CREATE TABLE claim_plan_date (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    plan_id  BIGINT       NOT NULL REFERENCES claim_plan (id),
    made_on  DATE         NOT NULL
);
CREATE UNIQUE INDEX claim_plan_date_unique ON claim_plan_date (plan_id, made_on);
