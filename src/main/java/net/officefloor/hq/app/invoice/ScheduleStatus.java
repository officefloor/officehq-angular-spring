package net.officefloor.hq.app.invoice;

/**
 * How an owing invoice on an instalment plan is keeping to its schedule: ON_TRACK while every instalment that has
 * fallen due is paid, BEHIND once an unpaid instalment is past its due date.
 */
public enum ScheduleStatus {
    ON_TRACK,
    BEHIND
}
