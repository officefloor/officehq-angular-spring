-- A person's grade is dated: person.grade is the first grade they were given, in force from the
-- beginning of time; each later grade recorded is a row here, in force from its from-date until the
-- next one. One grade per person and from-date.
CREATE TABLE person_grade (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    from_date DATE    NOT NULL,
    grade     CHAR(1) NOT NULL,
    PRIMARY KEY (person_id, from_date)
);
