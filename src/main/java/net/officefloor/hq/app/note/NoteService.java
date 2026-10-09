package net.officefloor.hq.app.note;

import java.time.Instant;
import java.util.List;
import net.officefloor.hq.app.project.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NoteService {

    private final NoteRepository notes;
    private final ProjectRepository projects;

    public NoteService(NoteRepository notes, ProjectRepository projects) {
        this.notes = notes;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> listForProject(Long projectId) {
        requireProject(projectId);
        return notes.findByTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(Note.PROJECT, projectId).stream()
                .map(NoteResponse::from).toList();
    }

    @Transactional
    public NoteResponse createForProject(Long projectId, NoteRequest request) {
        requireProject(projectId);
        Note saved = notes.save(new Note(Note.PROJECT, projectId, request.text().trim(), Instant.now()));
        return NoteResponse.from(saved);
    }

    private void requireProject(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job");
        }
    }
}
