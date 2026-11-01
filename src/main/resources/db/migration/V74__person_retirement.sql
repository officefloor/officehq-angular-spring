-- A retired person can be unretired. Each past retirement is kept, from its from-date to the sheet's
-- today it was ended on, with the sheet's today it was set on (null: set before this was kept). No
-- claim of theirs may be dated after the from-date and before the to-date of one.
CREATE TABLE person_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE NOT NULL,
    to_date   DATE NOT NULL,
    set_on    DATE
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);
