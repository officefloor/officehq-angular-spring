package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/notes")
@Validated
public class SheetNoteController {

    /** What the screen shows of a note: the sheet's today when it was added, who added it, its text. */
    public record NoteView(long id, String addedOn, String addedBy, String text) {
        static NoteView of(SheetNote note) {
            return new NoteView(note.getId(), note.getAddedOn().toString(), note.getAddedBy(), note.getText());
        }
    }

    /** A note, as typed on the form. */
    public record NoteForm(@NotBlank @Size(max = 1000) String text) {
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    /** The sheet's notes, newest first. */
    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(NoteView::of).toList();
    }

    /** Add a note to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid NoteForm form) {
        return NoteView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.text()));
    }
}
