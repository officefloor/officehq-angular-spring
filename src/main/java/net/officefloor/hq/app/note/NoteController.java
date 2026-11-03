package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.sheet.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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
public class NoteController {

    /** A note as the screen shows it: the sheet's today when it was added, who added it, its text. */
    public record NoteView(long id, LocalDate addedOn, String user, String text) {
        static NoteView of(Note n) {
            return new NoteView(n.getId(), n.getAddedOn(), n.getAddedBy(), n.getText());
        }
    }

    /** The note form: its text, refused when blank. */
    public record NoteForm(@NotBlank @Size(max = 2000) String text) {
    }

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    /** The notes, newest first; as of a date, those added while the sheet's today was on or before it. */
    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return service.list(sheetId.trim()).stream()
                .filter(n -> asOf == null || !n.getAddedOn().isAfter(asOf))
                .map(NoteView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody NoteForm form) {
        return NoteView.of(service.add(sheetId.trim(), user.trim(), form.text()));
    }
}
