-- A person's own terms dated from a day: the one with the latest from-date on or before a claim's date
-- is in force for it (null terms: none of their own then); before the first, the person's undated terms
-- apply, from the beginning of time.
CREATE TABLE person_terms (
    person_id  BIGINT  NOT NULL REFERENCES person (id),
    terms      INTEGER CHECK (terms >= 0),
    terms_from DATE    NOT NULL
);
CREATE INDEX person_terms_person ON person_terms (person_id);
