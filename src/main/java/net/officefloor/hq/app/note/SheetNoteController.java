package net.officefloor.hq.app.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/notes")
public class SheetNoteController {

    public record SheetNoteView(Long id, LocalDate addedOn, String addedBy, String text) {
        static SheetNoteView of(SheetNote n) {
            return new SheetNoteView(n.getId(), n.getAddedOn(), n.getAddedBy(), n.getText());
        }
    }

    public record SheetNoteForm(@NotNull @Size(max = 500) String text) {
    }

    private final SheetNoteService service;

    public SheetNoteController(SheetNoteService service) {
        this.service = service;
    }

    /** The sheet's notes, newest first. */
    @GetMapping
    public List<SheetNoteView> list(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.list(sheetId).stream().map(SheetNoteView::of).toList();
    }

    /** Add a note; a blank one is refused. */
    @PostMapping
    public SheetNoteView add(@PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody SheetNoteForm form, @SessionAttribute(SessionController.USER) String user) {
        return SheetNoteView.of(service.add(user, sheetId, form.text()));
    }
}
