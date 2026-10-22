-- A person's grade is dated: the grade on the person row is the first they were given, applying from
-- the beginning of time; each later grade is recorded here from its date, one per date, and holds
-- from that date until the next.
CREATE TABLE person_grade (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    from_date DATE    NOT NULL,
    grade     CHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    PRIMARY KEY (person_id, from_date)
);
