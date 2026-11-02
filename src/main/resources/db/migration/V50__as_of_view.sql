-- The sheet's history keeps its periods too: a revision of each period a change left different from
-- its last revision, stamped with the sheet's today then. Each person's fixed due-total is kept as
-- text: "person-id:total", separated by ";".
CREATE TABLE period_revision (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id      VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    period_id     BIGINT         NOT NULL,
    recorded_on   DATE           NOT NULL,
    name          VARCHAR(100)   NOT NULL,
    from_date     DATE           NOT NULL,
    to_date       DATE           NOT NULL,
    closed        BOOLEAN        NOT NULL,
    soft_closed   BOOLEAN        NOT NULL,
    closed_total  DECIMAL(14, 2),
    person_totals VARCHAR(4000)  NOT NULL
);
CREATE INDEX period_revision_sheet ON period_revision (sheet_id, id);

INSERT INTO period_revision (sheet_id, period_id, recorded_on, name, from_date, to_date, closed, soft_closed,
        closed_total, person_totals)
    SELECT p.sheet_id, p.id, s.today, p.name, p.from_date, p.to_date, p.closed, p.soft_closed, p.closed_total,
        COALESCE((SELECT LISTAGG(CAST(t.person_id AS VARCHAR) || ':' || CAST(t.total AS VARCHAR), ';')
            WITHIN GROUP (ORDER BY t.person_id) FROM period_person_total t WHERE t.period_id = p.id), '')
    FROM period p JOIN sheet s ON s.id = p.sheet_id ORDER BY p.id;

-- The sheet's today when a patron was named; null for one named before it was kept (named all along).
ALTER TABLE patron ADD COLUMN named_on DATE;
