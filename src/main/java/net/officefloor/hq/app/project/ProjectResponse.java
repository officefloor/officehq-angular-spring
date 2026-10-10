package net.officefloor.hq.app.project;

import java.time.LocalDate;

public record ProjectResponse(Long id, String name, String code, Long clientId, String clientName, boolean archived,
        ProjectStatus status, String currency, String description, boolean billable,
        boolean closed, LocalDate startDate, LocalDate endDate) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getCode(),
                project.getClient().getId(), project.getClient().getName(), project.isArchived(),
                project.getStatus(), project.getClient().getCurrency(), project.getDescription(),
                project.isBillable(), project.isClosed(),
                project.getStartDate(), project.getEndDate());
    }
}
