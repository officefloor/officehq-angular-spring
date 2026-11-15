-- A person's plan: a DRAFT claim of its amount is generated for each plan date (its start, then
-- every so many days) up to its end and the sheet's today. A person who is later merged or moved
-- away leaves their plans behind, so the person is not a reference.
CREATE TABLE plan (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id   BIGINT         NOT NULL,
    amount      DECIMAL(15, 2) NOT NULL,
    start_date  DATE           NOT NULL,
    every_days  INT            NOT NULL,
    end_date    DATE           NOT NULL
);

-- Each plan date a plan has made its claim for; a date with none yet is tried again on the next
-- generate. The claim itself may later go its own way, so it is not a reference.
CREATE TABLE plan_claim (
    plan_id  BIGINT NOT NULL REFERENCES plan (id),
    on_date  DATE   NOT NULL,
    claim_id BIGINT NOT NULL,
    PRIMARY KEY (plan_id, on_date)
);
