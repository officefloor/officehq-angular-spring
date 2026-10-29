-- The last rollover made on a sheet, kept so the steward who made it can undo it on the same sheet-today: who made it,
-- the sheet's today then, the fiscal year rolled over, the ids of the periods it generated (comma-separated) and the
-- opening balances it set (period:person:amount, comma-separated).
CREATE TABLE period_rollover (
    sheet_id    VARCHAR(100)   NOT NULL PRIMARY KEY REFERENCES sheet (id),
    made_by     VARCHAR(100)   NOT NULL,
    made_on     DATE           NOT NULL,
    fiscal_year INT            NOT NULL,
    period_ids  VARCHAR(10000) NOT NULL,
    openings    VARCHAR(100000) NOT NULL
);
