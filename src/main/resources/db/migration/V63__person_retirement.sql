-- A retired person can be unretired: each past retirement is kept, from its from-date to the sheet's
-- today it was ended on. A claim dated after the from-date and before the to-date stays refused.
CREATE TABLE person_retirement (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL,
    made_on   DATE
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);
