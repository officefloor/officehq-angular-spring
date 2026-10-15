package net.officefloor.hq.app.people;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    /** A group: its id on its sheet and its name. */
    public record GroupView(Long id, String name) {
        static GroupView of(PersonGroup g) {
            return new GroupView(g.getLocalId(), g.getName());
        }
    }

    public record NewGroup(@NotBlank String name) {
    }

    private final PersonService service;

    public GroupController(PersonService service) {
        this.service = service;
    }

    /** The groups on the request's sheet; blank or absent means the unnamed sheet. */
    @GetMapping
    public List<GroupView> list(@RequestParam(required = false) String sheet) {
        return service.groups(sheetId(sheet)).stream().map(GroupView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupView add(@RequestParam(required = false) String sheet, @Valid @RequestBody NewGroup group) {
        return GroupView.of(service.addGroup(sheetId(sheet), group.name()));
    }

    private static String sheetId(String sheet) {
        return sheet == null || sheet.isBlank() ? null : sheet.trim();
    }
}
