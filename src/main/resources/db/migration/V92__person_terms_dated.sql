-- A person's own terms can be dated: each dated terms are in force from their from-date until the next
-- one's; null terms are none of the person's own then. Before the earliest from-date, the person's undated
-- terms (person.terms) are in force. A person has at most one terms per from-date.
CREATE TABLE person_terms (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    terms     INTEGER
);
CREATE INDEX person_terms_person ON person_terms (person_id);
