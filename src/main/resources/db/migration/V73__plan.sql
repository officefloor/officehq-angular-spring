-- A person's plans: an amount, made a draft claim on each plan date from the start, every so many days, to the end.
-- Each plan date a claim was made on is kept once, so generating again makes none for it; a date the claim form
-- refused is not kept and is tried again the next time.
CREATE TABLE plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    amount     DECIMAL(14, 2) NOT NULL CHECK (amount >= 0),
    start_date DATE           NOT NULL,
    every_days INT            NOT NULL CHECK (every_days >= 1),
    end_date   DATE           NOT NULL,
    CHECK (end_date >= start_date)
);
CREATE INDEX plan_sheet ON plan (sheet_id, id);

CREATE TABLE plan_claim (
    plan_id BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    on_date DATE   NOT NULL,
    PRIMARY KEY (plan_id, on_date)
);
