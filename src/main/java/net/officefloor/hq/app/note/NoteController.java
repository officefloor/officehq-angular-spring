package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The notes on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/notes")
@Validated
public class NoteController {

    /** A note: its id, the sheet's today when it was added, who added it and its text. */
    public record NoteView(long id, LocalDate on, String by, String text) {
        static NoteView of(SheetNote n) {
            return new NoteView(n.getId(), n.getAddedOn(), n.getAddedBy(), n.getText());
        }
    }

    /** A note asked: its text (trimmed, not blank). */
    public record NoteRequest(@NotNull @Size(max = 1000) String text) {
    }

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.notes(sheetId.trim(), asOf).stream().map(NoteView::of).toList();
    }

    @PostMapping
    public List<NoteView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid NoteRequest request) {
        String sheet = sheetId.trim();
        service.add(sheet, user.trim(), request.text());
        return list(sheet, null);
    }
}
