package net.officefloor.hq.app.search;

import java.util.List;
import net.officefloor.hq.app.client.ClientResponse;
import net.officefloor.hq.app.project.ProjectResponse;

/** What one search found, grouped by kind. */
public record SearchResponse(List<ClientResponse> clients, List<ProjectResponse> projects) {
}
