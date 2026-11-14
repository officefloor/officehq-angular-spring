-- A person's own terms can be dated: each dated terms apply from their from-date, and those in force on
-- a date are the ones whose from-date is the latest on or before it. Dated terms of none (null) mean the
-- person has no own terms from that date. The person's undated terms (person.terms) apply from the
-- beginning of time. A person has at most one dated terms per from-date.
CREATE TABLE person_terms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    terms INT,
    from_date DATE NOT NULL
);
CREATE UNIQUE INDEX person_terms_person_from ON person_terms (person_id, from_date);
CREATE INDEX person_terms_sheet ON person_terms (sheet_id, person_id);
