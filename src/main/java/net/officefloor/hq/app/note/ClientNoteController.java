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

/** The notes written on a client, newest first. */
@RestController
@RequestMapping("/api/clients/{clientId}/notes")
public class ClientNoteController {

    private final NoteService service;

    public ClientNoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<NoteResponse> list(@PathVariable Long clientId) {
        return service.listForClient(clientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse create(@PathVariable Long clientId, @Valid @RequestBody NoteRequest request) {
        return service.createForClient(clientId, request);
    }
}
