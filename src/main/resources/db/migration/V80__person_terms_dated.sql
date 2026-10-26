-- A person's own terms can be dated: each kept with the date it applies from. The person's terms
-- column stays as their terms from the beginning of time. The terms in force on a date are those with
-- the latest from-date on or before it, else those from the beginning of time; null terms are none
-- (the grade's, else the sheet's). A person has at most one terms per from-date.
CREATE TABLE person_terms (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    terms     INTEGER,
    from_date DATE   NOT NULL
);
CREATE UNIQUE INDEX person_terms_from_unique ON person_terms (person_id, from_date);
