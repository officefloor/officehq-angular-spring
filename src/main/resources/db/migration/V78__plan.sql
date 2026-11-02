-- A person's plans: an amount claimed every so many days from a start date to an end date.
CREATE TABLE plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id),
    amount     DECIMAL(14, 2) NOT NULL,
    start_date DATE           NOT NULL,
    every_days INT            NOT NULL,
    end_date   DATE           NOT NULL,
    CONSTRAINT plan_every CHECK (every_days >= 1),
    CONSTRAINT plan_dates CHECK (end_date >= start_date)
);
CREATE INDEX plan_sheet ON plan (sheet_id, id);

-- The plan dates a plan has made its claim for, each at most once.
CREATE TABLE plan_date (
    plan_id BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    on_date DATE   NOT NULL,
    CONSTRAINT plan_date_unique UNIQUE (plan_id, on_date)
);
