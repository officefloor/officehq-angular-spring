-- The plans of a sheet's people: a DRAFT claim of the amount is made, by generate, for each plan date (the start,
-- then every so many days) up to the end and the sheet's today. Listed and generated in the order they were added.
CREATE TABLE plan (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id   VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    person_id  BIGINT         NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    amount     DECIMAL(12, 2) NOT NULL,
    start_date DATE           NOT NULL,
    every_days INT            NOT NULL,
    end_date   DATE           NOT NULL
);
CREATE INDEX plan_sheet ON plan (sheet_id, id);
-- The plan dates a plan has made its claim for, each at most once.
CREATE TABLE plan_date (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    plan_id   BIGINT       NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    plan_date DATE         NOT NULL
);
CREATE UNIQUE INDEX plan_date_plan_date ON plan_date (plan_id, plan_date);
