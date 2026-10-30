-- A person's plans: an amount claimed every N days from a start date up to an end date, and the plan dates a
-- claim has been made for (each at most once).
CREATE TABLE sheet_plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id  BIGINT NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL,
    start_date DATE NOT NULL,
    every_days INT NOT NULL,
    end_date   DATE NOT NULL
);

CREATE TABLE sheet_plan_claim (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES sheet_plan (id),
    on_date DATE NOT NULL,
    CONSTRAINT sheet_plan_claim_unique UNIQUE (plan_id, on_date)
);
