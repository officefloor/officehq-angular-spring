package net.officefloor.hq.app.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.AsOf;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/notes")
public class SheetNoteController {

    /** A note as the screen shows it. */
    public record NoteView(Long id, LocalDate addedOn, String by, String text) {
        static NoteView of(SheetNote note) {
            return new NoteView(note.getId(), note.getAddedOn(), note.getAddedBy(), note.getText());
        }
    }

    /** A note: its text. */
    public record NoteRequest(@Size(max = 1000) String text) {
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    /** The sheet's notes, the newest first; as of a date when the screen is set to one. */
    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(value = AsOf.HEADER, required = false) String asOfHeader) {
        return service.list(sheetId.trim(), AsOf.parse(asOfHeader)).stream().map(NoteView::of).toList();
    }

    /** Add a note to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated NoteRequest request) {
        return NoteView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.text()));
    }

    /** A blank text is refused. */
    @ExceptionHandler({NoteRefusedException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void refused() {
    }
}
