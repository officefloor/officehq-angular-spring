-- A person's past retirements, each from its from-date to the sheet's today when they were unretired. No claim
-- dated after the from-date and before the to-date can be added or saved for them.
CREATE TABLE person_retirement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL
);
