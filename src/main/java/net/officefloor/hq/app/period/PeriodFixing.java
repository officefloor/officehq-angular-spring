package net.officefloor.hq.app.period;

/** Published while a period is being closed or soft-closed, before it is saved, so what else is fixed
 *  with it can be fixed on it as it stands at that moment. */
public record PeriodFixing(Period period) {
}
