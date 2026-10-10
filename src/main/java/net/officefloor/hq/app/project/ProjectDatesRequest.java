package net.officefloor.hq.app.project;

import java.time.LocalDate;

/** Payload to set a job's start and end dates; leave either out to clear it. */
public record ProjectDatesRequest(LocalDate startDate, LocalDate endDate) {
}
