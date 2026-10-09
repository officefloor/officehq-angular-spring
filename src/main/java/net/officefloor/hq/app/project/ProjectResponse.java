package net.officefloor.hq.app.project;

public record ProjectResponse(Long id, String name, String code, Long clientId, String clientName, boolean archived,
        ProjectStatus status) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getCode(),
                project.getClient().getId(), project.getClient().getName(), project.isArchived(),
                project.getStatus());
    }
}
