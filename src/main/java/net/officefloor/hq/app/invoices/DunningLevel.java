package net.officefloor.hq.app.invoices;

/**
 * How far an overdue invoice is chased, worked out from the days since its due date and the reminders sent
 * (see {@link Invoice#dunningLevel}); never stored. Each level after NONE is the reminder sent at it, in order:
 * a level is never reached before the reminder of the one before it went out.
 */
public enum DunningLevel {
    NONE, REMINDER_1, REMINDER_2, FINAL, COLLECTIONS;

    /** The number of reminders sent once this level's reminder has gone out. */
    public int reminders() {
        return ordinal();
    }
}
