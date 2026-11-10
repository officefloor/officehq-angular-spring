package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing and adding the notes on a sheet. */
@RestController
@RequestMapping("/api/notes")
public class SheetNoteController {

    public record ListNotesRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddNoteRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 2000) String text) {
    }

    public record NoteResponse(long id, String text, String user, LocalDate date) {
    }

    private static NoteResponse response(SheetNote note) {
        return new NoteResponse(note.getId(), note.getText(), note.getAddedBy(), note.getAddedOn());
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<NoteResponse> list(@Valid @RequestBody ListNotesRequest req) {
        return service.list(req.sheet().trim()).stream().map(SheetNoteController::response).toList();
    }

    @PostMapping("/add")
    public NoteResponse add(@Valid @RequestBody AddNoteRequest req) {
        return response(service.add(req.sheet().trim(), req.user().trim(), req.text()));
    }
}
