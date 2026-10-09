package net.officefloor.hq.app.project;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) Long tagId, @RequestParam(required = false) ProjectStatus status) {
        return service.list(includeArchived, tagId, status);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody ProjectRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}/status")
    public ProjectResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ProjectStatusRequest request) {
        return service.changeStatus(id, request.status());
    }

    @PutMapping("/{id}/billable")
    public ProjectResponse setBillable(@PathVariable Long id, @Valid @RequestBody ProjectBillableRequest request) {
        return service.setBillable(id, request.billable());
    }

    @GetMapping("/{id}/budget")
    public ProjectBudgetResponse budget(@PathVariable Long id) {
        return service.budget(id);
    }

    @PutMapping("/{id}/budget")
    public ProjectBudgetResponse setBudget(@PathVariable Long id, @Valid @RequestBody ProjectBudgetRequest request) {
        return service.setBudget(id, request.budget());
    }

    @PostMapping("/{id}/archive")
    public ProjectResponse archive(@PathVariable Long id) {
        return service.archive(id);
    }

    @PostMapping("/{id}/restore")
    public ProjectResponse restore(@PathVariable Long id) {
        return service.restore(id);
    }
}
