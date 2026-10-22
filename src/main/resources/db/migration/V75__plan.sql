-- A plan on a person: an amount, claimed every N days from a start date to an end date (both included).
-- Generating makes a DRAFT claim for each plan date up to the sheet's today that has none yet; the
-- claims a plan made are kept by date, so a date is made once, and one the claim form refused is tried
-- again next time. A plan follows its person when they are merged into another.
CREATE TABLE plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(64)    NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL,
    start_on   DATE           NOT NULL,
    every_days INT            NOT NULL,
    end_on     DATE           NOT NULL
);
CREATE INDEX plan_sheet ON plan (sheet_id, id);

CREATE TABLE plan_claim (
    plan_id  BIGINT NOT NULL REFERENCES plan (id),
    on_date  DATE   NOT NULL,
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (plan_id, on_date)
);
