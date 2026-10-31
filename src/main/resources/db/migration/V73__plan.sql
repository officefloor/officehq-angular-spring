-- The plans on a sheet: a person's amount claimed every so many days from a start date up to an end date. Generating
-- makes a DRAFT claim for each plan date that has none yet; plan_claim keeps the dates a plan has made a claim for,
-- so a date is made once and a refused one is tried again next time.
CREATE TABLE plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(14, 2) NOT NULL,
    start_date DATE           NOT NULL,
    every_days INT            NOT NULL,
    end_date   DATE           NOT NULL
);
CREATE INDEX plan_sheet ON plan (sheet_id);

CREATE TABLE plan_claim (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT NOT NULL REFERENCES plan (id),
    claim_date DATE   NOT NULL,
    claim_id   BIGINT NOT NULL
);
CREATE UNIQUE INDEX plan_claim_plan_date ON plan_claim (plan_id, claim_date);
