package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.AsOfGuard;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A note as the screen shows it: the sheet's today when it was added, its user and its text. */
    public record NoteView(long id, String on, String by, String text) {
        static NoteView of(SheetNote n) {
            return new NoteView(n.getId(), n.getAddedOn().toString(), n.getAddedBy(), n.getText());
        }
    }

    /** A note as the form submits it: its text, not blank. */
    public record NoteForm(@NotBlank @Size(max = 1000) String text) {
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    /** The sheet's notes, newest first; as of a date, those added on or before it. */
    @GetMapping
    public List<NoteView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.list(sheetId, asOf).stream().map(NoteView::of).toList();
    }

    /** Add a note; resolves to the sheet's notes with it. */
    @PostMapping
    public List<NoteView> add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody NoteForm body) {
        service.add(sheetId, user.trim(), body.text().trim());
        return list(sheetId, user, null);
    }
}
