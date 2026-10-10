package net.officefloor.hq.app.project;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A job; {@code outstanding} is what is still owed on its sent invoices, or null where it is not worked out. */
public record ProjectResponse(Long id, String name, String code, Long clientId, String clientName, boolean archived,
        ProjectStatus status, String currency, String description, boolean billable,
        boolean closed, LocalDate startDate, LocalDate endDate, String fileRef, String category,
        BigDecimal outstanding) {

    public static ProjectResponse from(Project project) {
        return from(project, null);
    }

    public static ProjectResponse from(Project project, BigDecimal outstanding) {
        return new ProjectResponse(project.getId(), project.getName(), project.getCode(),
                project.getClient().getId(), project.getClient().getName(), project.isArchived(),
                project.getStatus(), project.getClient().getCurrency(), project.getDescription(),
                project.isBillable(), project.isClosed(),
                project.getStartDate(), project.getEndDate(), project.getFileRef(), project.getCategory(),
                outstanding);
    }
}
