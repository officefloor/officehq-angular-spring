package net.officefloor.hq.app.task;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Every job's tasks, grouped under the job they belong to. */
@RestController
public class TasksByJobController {

    private final TaskService service;

    public TasksByJobController(TaskService service) {
        this.service = service;
    }

    @GetMapping("/api/tasks/by-job")
    public List<TaskGroupResponse> listByJob() {
        return service.listByJob();
    }
}
