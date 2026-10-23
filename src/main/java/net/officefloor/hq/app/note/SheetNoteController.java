package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/notes")
@Validated
public class SheetNoteController {

    /** What the screen is told about a note: the sheet's today when added, who added it, its text. */
    public record NoteView(long id, LocalDate date, String user, String text) {

        static NoteView of(SheetNote note) {
            return new NoteView(note.getId(), note.getAddedOn(), note.getAddedBy(), note.getText());
        }
    }

    /** A note as entered on the form: a text, not blank. */
    public record NoteForm(@NotBlank @Size(max = 2000) String text) {
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    /** The sheet's notes, newest first. */
    @GetMapping
    public List<NoteView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(NoteView::of).toList();
    }

    /** Add a note to the sheet. */
    @PostMapping
    public NoteView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid NoteForm form) {
        return NoteView.of(service.add(sheetId, SignedIn.name(user), form.text()));
    }
}
