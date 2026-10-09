package net.officefloor.hq.app.project;

public record ProjectResponse(Long id, String name, Long clientId, String clientName) {

    static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(),
                project.getClient().getId(), project.getClient().getName());
    }
}
