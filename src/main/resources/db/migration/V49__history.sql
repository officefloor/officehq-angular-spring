-- The sheet's history: after each change that can move its figures, a revision of every claim,
-- person, rate and dated setting the change left different from its last revision (and of the
-- policy date), stamped with the sheet's today then. The sheet as it stood at the end of a day is
-- the last revision of each stamped on or before that day.
CREATE TABLE claim_revision (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id       BIGINT         NOT NULL,
    recorded_on    DATE           NOT NULL,
    person_id      BIGINT         NOT NULL,
    amount         DECIMAL(14, 2) NOT NULL,
    claim_date     DATE           NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    settled_due    DECIMAL(14, 2),
    stated         BOOLEAN        NOT NULL,
    stated_due     DECIMAL(14, 2),
    stated_na      BOOLEAN        NOT NULL,
    corrected_date DATE,
    due_by         DATE           NOT NULL,
    deferred_until DATE
);
CREATE INDEX claim_revision_sheet ON claim_revision (sheet_id, id);

-- A person's grades and holds are kept as text: each grade "from:grade" and each hold "from..to"
-- (a day left blank when there is none), separated by ";".
CREATE TABLE person_revision (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id     VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    person_id    BIGINT        NOT NULL,
    recorded_on  DATE          NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    grades       VARCHAR(4000) NOT NULL,
    holds        VARCHAR(4000) NOT NULL,
    retired_from DATE,
    terms        INT
);
CREATE INDEX person_revision_sheet ON person_revision (sheet_id, id);

CREATE TABLE rate_revision (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    grade       VARCHAR(1)   NOT NULL,
    from_date   DATE,
    recorded_on DATE         NOT NULL,
    pct         DECIMAL(7, 2) NOT NULL,
    to_date     DATE
);
CREATE INDEX rate_revision_sheet ON rate_revision (sheet_id, id);

CREATE TABLE setting_revision (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    setting_key VARCHAR(50)    NOT NULL,
    from_date   DATE           NOT NULL,
    recorded_on DATE           NOT NULL,
    setting     DECIMAL(14, 2)
);
CREATE INDEX setting_revision_sheet ON setting_revision (sheet_id, id);

CREATE TABLE sheet_revision (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    recorded_on DATE         NOT NULL,
    policy_date DATE
);
CREATE INDEX sheet_revision_sheet ON sheet_revision (sheet_id, id);

-- What a sheet holds already is its first revision, stamped with its today.
INSERT INTO claim_revision (sheet_id, claim_id, recorded_on, person_id, amount, claim_date, status, settled_due,
        stated, stated_due, stated_na, corrected_date, due_by, deferred_until)
    SELECT c.sheet_id, c.id, s.today, c.person_id, c.amount, c.claim_date, c.status, c.settled_due, c.stated,
        c.stated_due, c.stated_na, c.corrected_date, c.due_by, c.deferred_until
    FROM claim c JOIN sheet s ON s.id = c.sheet_id ORDER BY c.id;
INSERT INTO person_revision (sheet_id, person_id, recorded_on, name, grades, holds, retired_from, terms)
    SELECT p.sheet_id, p.id, s.today, p.name,
        COALESCE((SELECT LISTAGG(COALESCE(CAST(g.from_date AS VARCHAR), '') || ':' || g.grade, ';')
            WITHIN GROUP (ORDER BY g.from_date NULLS FIRST) FROM person_grade g WHERE g.person_id = p.id), ''),
        COALESCE((SELECT LISTAGG(COALESCE(CAST(h.from_date AS VARCHAR), '') || '..'
            || COALESCE(CAST(h.to_date AS VARCHAR), ''), ';')
            WITHIN GROUP (ORDER BY h.from_date NULLS FIRST) FROM person_hold h WHERE h.person_id = p.id), ''),
        p.retired_from, p.terms
    FROM person p JOIN sheet s ON s.id = p.sheet_id ORDER BY p.id;
INSERT INTO rate_revision (sheet_id, grade, from_date, recorded_on, pct, to_date)
    SELECT r.sheet_id, r.grade, r.from_date, s.today, r.pct, r.to_date
    FROM rate r JOIN sheet s ON s.id = r.sheet_id ORDER BY r.id;
INSERT INTO setting_revision (sheet_id, setting_key, from_date, recorded_on, setting)
    SELECT v.sheet_id, v.setting_key, v.from_date, s.today, v.setting
    FROM setting_value v JOIN sheet s ON s.id = v.sheet_id;
INSERT INTO sheet_revision (sheet_id, recorded_on, policy_date)
    SELECT id, today, policy_date FROM sheet WHERE policy_date IS NOT NULL;

-- A statement may be taken as of an earlier day than the sheet's today: that day, null when it was
-- taken as of the sheet's today.
ALTER TABLE statement ADD COLUMN as_of DATE;
