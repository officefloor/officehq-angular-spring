package net.officefloor.hq.app.task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final ChecklistItemRepository checklistItems;
    private final ProjectRepository projects;
    private final Audit audit;

    public TaskService(TaskRepository tasks, ChecklistItemRepository checklistItems, ProjectRepository projects,
            Audit audit) {
        this.tasks = tasks;
        this.checklistItems = checklistItems;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listForProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job");
        }
        Map<Long, List<ChecklistItemResponse>> checklists = checklistItems.findByTaskProjectIdOrderById(projectId)
                .stream().map(ChecklistItemResponse::from)
                .collect(Collectors.groupingBy(ChecklistItemResponse::taskId));
        return tasks.findByProjectIdOrderById(projectId).stream()
                .map(t -> TaskResponse.from(t, checklists.getOrDefault(t.getId(), List.of()))).toList();
    }

    /** The tasks of every job still in use, grouped under the job they belong to. */
    @Transactional(readOnly = true)
    public List<TaskGroupResponse> listByJob() {
        Map<Long, List<ChecklistItemResponse>> checklists = checklistItems.findAllByOrderById().stream()
                .map(ChecklistItemResponse::from)
                .collect(Collectors.groupingBy(ChecklistItemResponse::taskId));
        Map<Project, List<TaskResponse>> grouped = tasks.findAllByOrderByProjectIdAscIdAsc().stream()
                .filter(t -> !t.getProject().isArchived())
                .collect(Collectors.groupingBy(Task::getProject, LinkedHashMap::new, Collectors.mapping(
                        t -> TaskResponse.from(t, checklists.getOrDefault(t.getId(), List.of())),
                        Collectors.toList())));
        return grouped.entrySet().stream()
                .map(e -> new TaskGroupResponse(e.getKey().getId(), e.getKey().getName(), e.getKey().getCode(),
                        e.getValue()))
                .toList();
    }

    @Transactional
    public TaskResponse create(Long projectId, TaskRequest request) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job"));
        Task saved = tasks.save(new Task(project, request.title().trim(), request.dueDate(),
                request.assignee() == null || request.assignee().isBlank() ? null : request.assignee().trim(),
                request.priority()));
        return TaskResponse.from(saved, List.of());
    }

    /** Ticks a task off (or reopens it) and records the change in the audit log. */
    @Transactional
    public TaskResponse toggle(Long projectId, Long taskId) {
        Task task = findTask(projectId, taskId);
        task.toggle();
        audit.record("TASK_TOGGLED id=" + task.getId() + " done=" + task.isDone());
        return TaskResponse.from(task, checklistItems.findByTaskIdOrderById(taskId).stream()
                .map(ChecklistItemResponse::from).toList());
    }

    /** Adds a sub-item to a task's checklist. */
    @Transactional
    public ChecklistItemResponse addChecklistItem(Long projectId, Long taskId, ChecklistItemRequest request) {
        Task task = findTask(projectId, taskId);
        ChecklistItem saved = checklistItems.save(new ChecklistItem(task, request.text().trim()));
        audit.record("CHECKLIST_ITEM_ADDED id=" + saved.getId() + " taskId=" + taskId);
        return ChecklistItemResponse.from(saved);
    }

    /** Ticks a checklist item off (or reopens it) and records the change in the audit log. */
    @Transactional
    public ChecklistItemResponse toggleChecklistItem(Long projectId, Long taskId, Long itemId) {
        ChecklistItem item = checklistItems.findByIdAndTaskIdAndTaskProjectId(itemId, taskId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown checklist item"));
        item.toggle();
        audit.record("CHECKLIST_ITEM_TOGGLED id=" + item.getId() + " done=" + item.isDone());
        return ChecklistItemResponse.from(item);
    }

    private Task findTask(Long projectId, Long taskId) {
        return tasks.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task"));
    }
}
