package net.officefloor.hq.app.task;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The task list kept for a project. */
@RestController
@RequestMapping("/api/projects/{projectId}/tasks")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<TaskResponse> list(@PathVariable Long projectId) {
        return service.listForProject(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@PathVariable Long projectId, @Valid @RequestBody TaskRequest request) {
        return service.create(projectId, request);
    }

    @PostMapping("/{taskId}/toggle")
    public TaskResponse toggle(@PathVariable Long projectId, @PathVariable Long taskId) {
        return service.toggle(projectId, taskId);
    }
}
