package net.officefloor.hq.app.tag;

import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.project.Project;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TagService {

    private final TagRepository tags;
    private final ProjectRepository projects;
    private final Audit audit;

    public TagService(TagRepository tags, ProjectRepository projects, Audit audit) {
        this.tags = tags;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TagResponse> list() {
        return tags.findAllByOrderByNameAsc().stream().map(TagResponse::from).toList();
    }

    @Transactional
    public TagResponse create(TagRequest request) {
        String name = request.name().trim();
        if (tags.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tag already exists");
        }
        return TagResponse.from(tags.save(new Tag(name)));
    }

    @Transactional(readOnly = true)
    public List<TagResponse> listForProject(Long projectId) {
        return responses(findProject(projectId));
    }

    /** Puts a tag on a project, recording it in the audit log. */
    @Transactional
    public List<TagResponse> tag(Long projectId, Long tagId) {
        Project project = findProject(projectId);
        Tag tag = tags.findById(tagId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tag"));
        if (project.getTags().add(tag)) {
            projects.flush();
            audit.record("PROJECT_TAGGED project=" + projectId + " tag=" + tagId);
        }
        return responses(project);
    }

    /** Takes a tag off a project, recording it in the audit log. */
    @Transactional
    public List<TagResponse> untag(Long projectId, Long tagId) {
        Project project = findProject(projectId);
        if (project.getTags().removeIf(t -> t.getId().equals(tagId))) {
            projects.flush();
            audit.record("PROJECT_UNTAGGED project=" + projectId + " tag=" + tagId);
        }
        return responses(project);
    }

    private Project findProject(Long projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown project"));
    }

    private static List<TagResponse> responses(Project project) {
        return project.getTags().stream()
                .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                .map(TagResponse::from).toList();
    }
}
