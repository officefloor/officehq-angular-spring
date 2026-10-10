package net.officefloor.hq.app.task;

import java.util.List;

/** A job and the tasks that belong to it. */
public record TaskGroupResponse(Long projectId, String projectName, String projectCode, List<TaskResponse> tasks) {
}
