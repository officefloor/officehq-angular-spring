package net.officefloor.hq.app.task;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskService {

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final Audit audit;

    public TaskService(TaskRepository tasks, ProjectRepository projects, Audit audit) {
        this.tasks = tasks;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project");
        }
        return tasks.findByProjectIdOrderById(projectId).stream().map(TaskResponse::from).toList();
    }

    @Transactional
    public TaskResponse create(Long projectId, TaskRequest request) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
        Task saved = tasks.save(new Task(project, request.title().trim()));
        return TaskResponse.from(saved);
    }

    /** Ticks a task off (or reopens it) and records the change in the audit log. */
    @Transactional
    public TaskResponse toggle(Long projectId, Long taskId) {
        Task task = tasks.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task"));
        task.toggle();
        audit.record("TASK_TOGGLED id=" + task.getId() + " done=" + task.isDone());
        return TaskResponse.from(task);
    }
}
