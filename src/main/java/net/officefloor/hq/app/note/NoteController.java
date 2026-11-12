package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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

/** The notes of a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/notes")
@Validated
public class NoteController {

    /** A note as shown, the date it was added as yyyy-mm-dd. */
    public record NoteView(Long id, String date, String user, String text) {

        static NoteView of(Note n) {
            return new NoteView(n.getId(), n.getAddedOn().toString(), n.getAddedBy(), n.getText());
        }
    }

    /** The note's text: surrounding spaces are removed, and it must not then be blank. */
    public record NoteRequest(@NotNull @NotBlank @Size(max = 1000) String text) {
    }

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    /** The sheet's notes, newest first. */
    @GetMapping
    public List<NoteView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(NoteView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid NoteRequest request) {
        return NoteView.of(service.add(sheetId.trim(), user.trim(), request.text().trim()));
    }
}
