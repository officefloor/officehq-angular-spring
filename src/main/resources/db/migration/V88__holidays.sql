-- The workspace's holiday calendar: once the 'holidays' setting is present (even with no rows here),
-- days in payment terms are business days, Monday to Friday and not one of these dates.
CREATE TABLE holiday (
    holiday_on DATE PRIMARY KEY
);
