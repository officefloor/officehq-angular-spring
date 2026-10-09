package net.officefloor.hq.app.note;

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

/** The notes written on a project, newest first. */
@RestController
@RequestMapping("/api/projects/{projectId}/notes")
public class ProjectNoteController {

    private final NoteService service;

    public ProjectNoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<NoteResponse> list(@PathVariable Long projectId) {
        return service.listForProject(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse create(@PathVariable Long projectId, @Valid @RequestBody NoteRequest request) {
        return service.createForProject(projectId, request);
    }
}
