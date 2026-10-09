package net.officefloor.hq.app.tag;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tags put on a project. */
@RestController
@RequestMapping("/api/projects/{projectId}/tags")
public class ProjectTagController {

    private final TagService service;

    public ProjectTagController(TagService service) {
        this.service = service;
    }

    @GetMapping
    public List<TagResponse> list(@PathVariable Long projectId) {
        return service.listForProject(projectId);
    }

    @PutMapping("/{tagId}")
    public List<TagResponse> add(@PathVariable Long projectId, @PathVariable Long tagId) {
        return service.tag(projectId, tagId);
    }

    @DeleteMapping("/{tagId}")
    public List<TagResponse> remove(@PathVariable Long projectId, @PathVariable Long tagId) {
        return service.untag(projectId, tagId);
    }
}
