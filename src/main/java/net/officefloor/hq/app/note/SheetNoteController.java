package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/notes")
public class SheetNoteController {

    /** A note on the sheet: the sheet's today when it was added, who added it, and its text. */
    public record NoteView(Long id, LocalDate addedOn, String addedBy, String text) {
        static NoteView of(SheetNote n) {
            return new NoteView(n.getId(), n.getAddedOn(), n.getAddedBy(), n.getText());
        }
    }

    /** A note's text (not blank). */
    public record NoteRequest(@NotBlank @Size(max = 1000) String text) {}

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<NoteView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.notes(sheetId).stream().map(NoteView::of).toList();
    }

    @PostMapping
    public NoteView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid NoteRequest request) {
        return NoteView.of(service.add(sheetId, user, request.text()));
    }
}
