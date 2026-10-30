-- A person's own terms, each from a date: the one whose from-date is the latest on or before a date is in
-- force then (terms null: none from that date, their grade's or the sheet's); before the first of them, the
-- terms on the person (no from-date) apply from the beginning of time.
CREATE TABLE person_terms (
    person_id BIGINT NOT NULL REFERENCES person (id),
    terms     INT,
    from_date DATE   NOT NULL
);
CREATE INDEX person_terms_person ON person_terms (person_id);
