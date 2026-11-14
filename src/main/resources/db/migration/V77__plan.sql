-- A person's plan: a DRAFT claim of its amount for every plan date (its start, then every so many
-- days) up to its end, made by the sheet's generate. made_on is the sheet's today when it was added.
CREATE TABLE plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    amount DECIMAL(12, 2) NOT NULL,
    start_date DATE NOT NULL,
    every_days INT NOT NULL,
    end_date DATE NOT NULL,
    made_on DATE NOT NULL
);
CREATE INDEX plan_sheet ON plan (sheet_id);

-- Each plan date a plan has made its claim for, with the sheet's today when it was made.
CREATE TABLE plan_claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    plan_date DATE NOT NULL,
    claim_id BIGINT NOT NULL,
    made_on DATE NOT NULL,
    CONSTRAINT plan_claim_once UNIQUE (plan_id, plan_date)
);
