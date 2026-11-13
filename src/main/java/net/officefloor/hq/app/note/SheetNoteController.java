package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.history.AsOf;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A sheet's notes. The signed-in user's name comes in the X-User header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/notes")
public class SheetNoteController {

    public record NoteView(long id, String date, String user, String text) {

        static NoteView of(SheetNote n) {
            return new NoteView(n.getId(), n.getAddedOn().toString(), n.getAddedBy(), n.getText());
        }
    }

    public record NoteRequest(@Size(max = 2000) String text) {
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    /** The sheet's notes, newest first; those added by the end of a date when the screen views the sheet as
     *  of one. */
    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestHeader(value = AsOf.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.list(sheetId.trim(), asOf).stream().map(NoteView::of).toList();
    }

    /** Add a note to the sheet. */
    @PostMapping
    public NoteView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid NoteRequest request) {
        return NoteView.of(service.add(sheetId.trim(), user.trim(), request.text()));
    }

    /** A note that cannot be added is refused with the reason, for the screen to show. */
    @ExceptionHandler(NoteRefusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> refused(NoteRefusedException e) {
        return Map.of("message", e.getMessage());
    }
}
