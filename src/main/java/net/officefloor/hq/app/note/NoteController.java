package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The notes on a sheet. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/notes")
@Validated
public class NoteController {

    /** A note to add: its text (blank is refused). */
    public record NoteForm(@Size(max = 5000) String text) {
    }

    /** A note as shown: the sheet's today when it was added, who added it and its text. */
    public record NoteView(LocalDate on, String by, String text) {

        static NoteView of(SheetNote n) {
            return new NoteView(n.getAddedOn(), n.getAddedBy(), n.getText());
        }
    }

    public record Refusal(String message) {
    }

    private final SheetNotes notes;

    public NoteController(SheetNotes notes) {
        this.notes = notes;
    }

    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return notes.list(sheetId.trim()).stream().map(NoteView::of).toList();
    }

    /** Add a note; answers with every note on the sheet, newest first. */
    @PostMapping
    public List<NoteView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody NoteForm form) {
        String sheet = sheetId.trim();
        notes.add(sheet, user.trim(), form.text());
        return list(sheet);
    }

    @ExceptionHandler(NoteRefused.class)
    public ResponseEntity<Refusal> refused(NoteRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }
}
