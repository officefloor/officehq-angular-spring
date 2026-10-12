-- The dunning reminders sent on an overdue invoice: one row per reminder, the day it went out. The first is
-- REMINDER_1, the second REMINDER_2, the third FINAL and the fourth COLLECTIONS.
CREATE TABLE invoice_reminder (
    invoice_id BIGINT NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    sent_on    DATE   NOT NULL
);
