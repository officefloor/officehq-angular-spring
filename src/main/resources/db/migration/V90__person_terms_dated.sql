-- A person's own terms may be dated: each row records the terms applying from its from-date (null terms: none of
-- their own from then, so their grade's or the sheet's apply). The person's terms column stays the terms from the
-- beginning of time. The terms in force on a date are those with the latest from-date on or before it, else those
-- from the beginning of time. A person has at most one row per from-date.
CREATE TABLE person_terms (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    terms     INT CHECK (terms >= 0),
    made_on   DATE
);
CREATE UNIQUE INDEX person_terms_from ON person_terms (person_id, from_date);
