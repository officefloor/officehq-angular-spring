package net.officefloor.hq.app.person;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.asof.AsOfService;
import net.officefloor.hq.app.session.SessionController;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.settings.ViewerPasses;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people")
public class PersonController {

    /**
     * A person as listed: the grade is the one in force on the sheet's today, the grades every one recorded, the
     * terms their own in force on the sheet's today (null: none), the sheet they were transferred from (null: none); clashing when their name or an alias is on another sheet of the sheet's tree.
     */
    public record PersonView(Long id, String name, String grade, List<GradeView> grades, long version,
            LocalDate retiredFrom, boolean held, List<HoldView> holds, Integer terms, String contact,
            List<String> aliases, boolean clashing, String originSheet, List<RetirementView> pastRetirements,
            List<String> tags, List<SnapshotView> snapshots, String flag, String approver) {
        public static PersonView of(Person p, LocalDate today, Set<Long> clashing,
                Map<Long, List<SnapshotView>> snapshots) {
            return new PersonView(p.getId(), p.getName(), p.gradeOn(today),
                    p.getGrades().stream().map(GradeView::of).toList(), p.getVersion(), p.getRetiredFrom(),
                    p.isHeld(), p.getHolds().stream().map(HoldView::of).toList(), p.termsOn(today),
                    p.getContact(), p.getAliases(), clashing.contains(p.getId()),
                    p.getOriginSheet(), p.getPastRetirements().stream().map(RetirementView::of).toList(),
                    ClaimService.tagList(p.getTags()), snapshots.getOrDefault(p.getId(), List.of()),
                    p.flagOn(today), p.getApprover());
        }
    }

    /** A snapshot line of a person: the day being left when it was written, and their due-total then (null: n/a). */
    public record SnapshotView(LocalDate day, String dueTotal) {
        public static SnapshotView of(PersonSnapshot s) {
            return new SnapshotView(s.getDay(), s.getDueTotal() == null ? null : s.getDueTotal().toPlainString());
        }
    }

    /** A retirement the person was unretired from: from its from-date to the day they were unretired. */
    public record RetirementView(LocalDate from, LocalDate to) {
        public static RetirementView of(PastRetirement r) {
            return new RetirementView(r.getFrom(), r.getTo());
        }
    }

    /** A hold on a person from its from-date to its to-date (null: with no end). */
    public record HoldView(LocalDate from, LocalDate to) {
        public static HoldView of(DatedHold h) {
            return new HoldView(h.getFrom(), h.getTo());
        }
    }

    /** A grade a person has from its from-date (null: from the beginning of time). */
    public record GradeView(String grade, LocalDate from) {
        public static GradeView of(DatedGrade g) {
            return new GradeView(g.getGrade(), g.getFrom());
        }
    }

    /**
     * A person's fields as the form submits them; the name is stored without surrounding spaces, the terms are whole
     * days, 0 or more, or null for none, from the terms from-date (null: the beginning of time); the contact is stored without surrounding spaces, lower-cased, blank or
     * null for none; the tags are comma separated, blank or null for none.
     */
    public record PersonForm(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, @Min(0) @Max(99999) Integer terms,
            LocalDate termsFrom, @Size(max = 200) String contact, @Size(max = 1000) String tags) {
    }

    /**
     * An edit of a person: its fields, the date its grade applies from (missing: the sheet's today) and the version
     * of the person the editor loaded, their own terms (null: none) from the terms from-date (missing: from the beginning of time) and their contact (blank or null: none) and their tags, comma separated (blank: none; null: kept as they are).
     */
    public record PersonEdit(@NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[ABC]") String grade, LocalDate gradeFrom, @NotNull Long version,
            @Min(0) @Max(99999) Integer terms, LocalDate termsFrom, @Size(max = 200) String contact,
            @Size(max = 1000) String tags) {
    }

    /** One person named as another's patron. */
    public record PatronView(Long id, Long personId, Long patronId) {
        public static PatronView of(Patron p) {
            return new PatronView(p.getId(), p.getPersonId(), p.getPatronId());
        }
    }

    /** A patron to add: the id of another person on the sheet. */
    public record PatronForm(@NotNull Long patronId) {
    }

    /** An alias to give a person; stored without surrounding spaces. */
    public record AliasForm(@NotBlank @Size(max = 200) String alias) {
    }

    /** A merge: the id of the other person on the sheet the person is merged into. */
    public record MergeForm(@NotNull Long intoId) {
    }

    /** A transfer: the id of the sheet the person is transferred to. */
    public record TransferForm(@NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String toSheetId) {
    }

    /** A retirement: the date it takes effect; a missing date takes the sheet's today. */
    public record MoveForm(@NotNull @Pattern(regexp = "up|down") String direction) {
    }

    public record RetireForm(LocalDate from) {
    }

    /** A hold: the date it starts (missing: the sheet's today) and the date it ends (missing: with no end). */
    public record HoldForm(LocalDate from, LocalDate to) {
    }

    /** A flag: its reason and the last day it is in force (missing: the sheet's today). */
    public record FlagForm(String reason, LocalDate until) {
    }

    /** An approver: the user name who may approve or reject the person's PENDING claims. */
    public record ApproverForm(String user) {
    }

    /** A grade to give the people with the ids together, from its from-date (missing: the sheet's today). */
    public record BulkGradeForm(@NotEmpty Set<Long> ids, @NotNull @Pattern(regexp = "[ABC]") String grade,
            LocalDate from) {
    }

    /** A retirement for the people with the ids together, from its from-date (missing: the sheet's today). */
    public record BulkRetireForm(@NotEmpty Set<Long> ids, LocalDate from) {
    }

    /** The people a bulk retire retired, in list order. */
    public record BulkRetireView(List<PersonView> retired) {
    }

    /** The people a bulk grade changed, in list order. */
    public record BulkGradeView(List<PersonView> graded) {
    }

    /** The import box: lines of name,grade,terms,contact. */
    public record ImportForm(@NotNull @Size(max = 100_000) String text) {
    }

    /** What an import did: the people added and the people updated, each as listed now. */
    public record ImportView(List<PersonView> added, List<PersonView> updated) {
    }

    /** Whether the signed-in user is to watch the person. */
    public record WatchForm(@NotNull Boolean on) {
    }

    /** Whether the signed-in user watches the person now. */
    public record WatchView(Long personId, boolean watched) {
    }

    private final PersonService service;
    private final PersonMergeService merges;
    private final PersonTransferService transfers;
    private final AsOfService asOf;
    private final WatchService watches;
    private final SettingsService settings;
    private final PersonImportService imports;

    public PersonController(PersonService service, PersonMergeService merges,
            PersonTransferService transfers, AsOfService asOf, WatchService watches, SettingsService settings,
            PersonImportService imports) {
        this.service = service;
        this.merges = merges;
        this.transfers = transfers;
        this.asOf = asOf;
        this.watches = watches;
        this.settings = settings;
        this.imports = imports;
    }

    /** All the people on the sheet, in list order. */
    @GetMapping
    public List<PersonView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        LocalDate today = service.today(sheetId);
        Set<Long> clashing = service.clashing(sheetId);
        Map<Long, List<SnapshotView>> snapshots = service.snapshotLines(sheetId);
        return service.list(sheetId).stream().map(p -> PersonView.of(p, today, clashing, snapshots)).toList();
    }

    /** Each person's snapshot lines on the sheet, by person id, oldest first, as the store holds them now. */
    @GetMapping("/snapshots")
    public Map<Long, List<SnapshotView>> snapshots(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.snapshotLines(sheetId);
    }

    @PostMapping
    public PersonView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody PersonForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.add(user, sheetId, form.name(), form.grade(), form.terms(),
                form.termsFrom(), form.contact(), form.tags()));
    }

    /**
     * Adds a person for each pasted line, or updates the person it names, in order, all or none; the people added and
     * the people updated, each in the order of their lines.
     */
    @PostMapping("/import")
    public ImportView importPeople(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody ImportForm form,
            @SessionAttribute(SessionController.USER) String user) {
        PersonImportService.Result result = imports.importPeople(user, sheetId, form.text());
        LocalDate today = service.today(sheetId);
        Set<Long> clashing = service.clashing(sheetId);
        Map<Long, List<SnapshotView>> snapshots = service.snapshotLines(sheetId);
        return new ImportView(result.added().stream().map(p -> PersonView.of(p, today, clashing, snapshots)).toList(),
                result.updated().stream().map(p -> PersonView.of(p, today, clashing, snapshots)).toList());
    }

    @PutMapping("/{id}")
    public PersonView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody PersonEdit form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId,
                service.update(user, sheetId, id, form.version(), form.name(), form.grade(), form.gradeFrom(),
                        form.terms(), form.termsFrom(), form.contact(), form.tags()));
    }

    /** Gives the person an alias; refused when blank or when it is a name or alias on the sheet. */
    @PostMapping("/{id}/aliases")
    public PersonView addAlias(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody AliasForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.addAlias(user, sheetId, id, form.alias()));
    }

    /**
     * A steward merges the person into another person of the sheet, who takes what they had; the merged person
     * leaves the sheet. Refused for the same person, or when either of them is retired. The person merged into.
     */
    @PostMapping("/{id}/merge")
    public PersonView merge(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody MergeForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, merges.merge(user, sheetId, id, form.intoId()));
    }

    /**
     * A steward transfers the person, with their claims, to another sheet; they leave this one, and are merged there
     * into the person with their name or an alias. Refused for a retired person, this sheet's own id, and a contact
     * already on the other sheet.
     */
    @PostMapping("/{id}/transfer")
    public void transfer(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody TransferForm form,
            @SessionAttribute(SessionController.USER) String user) {
        transfers.transfer(user, sheetId, id, form.toSheetId());
        // the request's own sheet is stored as it now stands once answered; the other sheet changed too
        asOf.capture(form.toSheetId());
    }

    /**
     * The steward who transferred the person to this sheet undoes it on the sheet's today they arrived: they are
     * transferred back to the sheet they came from. Refused on a later day, for anyone else, and for a person who did
     * not arrive by transfer.
     */
    @PostMapping("/{id}/transfer-undo")
    public void transferUndo(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id,
            @SessionAttribute(SessionController.USER) String user) {
        String from = transfers.undo(user, sheetId, id);
        // the request's own sheet is stored as it now stands once answered; the sheet they went back to changed too
        asOf.capture(from);
    }

    /**
     * Swaps the person with the one above them in the list (direction up) or below (down); refused for the top person
     * moving up and the bottom one moving down. All the people on the sheet, in list order.
     */
    @PostMapping("/{id}/move")
    public List<PersonView> move(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody MoveForm form,
            @SessionAttribute(SessionController.USER) String user) {
        service.move(user, sheetId, id, form.direction().equals("up"));
        return list(sheetId);
    }

    @PostMapping("/{id}/retire")
    public PersonView retire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody RetireForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.retire(user, sheetId, id, form.from()));
    }

    /** Unretires a retired person; their retirement is kept as a past one, to the sheet's today. */
    @PostMapping("/{id}/unretire")
    public PersonView unretire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.unretire(user, sheetId, id));
    }

    /** Puts a hold on the person, from its from-date to its to-date; the holds already put are kept. */
    @PostMapping("/{id}/hold")
    public PersonView hold(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody HoldForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.hold(user, sheetId, id, form.from(), form.to()));
    }

    /** A steward flags the person with a reason until a date; only a steward may open their claims meanwhile. */
    @PostMapping("/{id}/flag")
    public PersonView flag(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody FlagForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.flag(user, sheetId, id, form.reason(), form.until()));
    }

    /** Sets the user who may approve or reject the person's PENDING claims as a steward may. */
    @PostMapping("/{id}/approver")
    public PersonView approver(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @RequestBody ApproverForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return view(sheetId, service.setApprover(user, sheetId, id, form.user()));
    }

    /**
     * Gives the people the grade from the from-date together, in list order, as each one's edit would. A viewer, or
     * anyone on an archived sheet, goes through with none changed.
     */
    @PostMapping("/bulk-grade")
    @ViewerPasses
    public BulkGradeView bulkGrade(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkGradeForm form,
            @SessionAttribute(SessionController.USER) String user) {
        if (settings.isReadOnly(sheetId, user)) {
            return new BulkGradeView(List.of());
        }
        List<Person> graded = service.bulkGrade(user, sheetId, form.ids(), form.grade(), form.from());
        LocalDate today = service.today(sheetId);
        Set<Long> clashing = service.clashing(sheetId);
        Map<Long, List<SnapshotView>> snapshots = service.snapshotLines(sheetId);
        return new BulkGradeView(graded.stream().map(p -> PersonView.of(p, today, clashing, snapshots)).toList());
    }

    /**
     * Retires the people from the from-date together, in list order, as each one's retire would. A viewer, or anyone
     * on an archived sheet, goes through with none retired.
     */
    @PostMapping("/bulk-retire")
    @ViewerPasses
    public BulkRetireView bulkRetire(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @Valid @RequestBody BulkRetireForm form,
            @SessionAttribute(SessionController.USER) String user) {
        if (settings.isReadOnly(sheetId, user)) {
            return new BulkRetireView(List.of());
        }
        List<Person> retired = service.bulkRetire(user, sheetId, form.ids(), form.from());
        LocalDate today = service.today(sheetId);
        Set<Long> clashing = service.clashing(sheetId);
        Map<Long, List<SnapshotView>> snapshots = service.snapshotLines(sheetId);
        return new BulkRetireView(retired.stream().map(p -> PersonView.of(p, today, clashing, snapshots)).toList());
    }

    /** The person as listed on the sheet's today. */
    private PersonView view(String sheetId, Person person) {
        return PersonView.of(person, service.today(sheetId), service.clashing(sheetId), service.snapshotLines(sheetId));
    }

    /** The ids of the people on the sheet the signed-in user watches, in the order they started watching. */
    /** A person found by a tree search: their sheet, name, grade in force today and due-total (two decimals, or n/a). */
    public record FoundView(String sheetId, String name, String grade, String dueTotal) {
        static FoundView of(PersonService.Found f) {
            return new FoundView(f.sheetId(), f.name(), f.grade(), f.dueTotal() == null ? "n/a" : f.dueTotal().toPlainString());
        }
    }

    /** Every person on the sheet or its tree whose name or an alias contains the text, ignoring case. */
    @GetMapping("/search")
    public List<FoundView> search(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @RequestParam(defaultValue = "") @Size(max = 200) String text) {
        return service.searchTree(sheetId, text).stream().map(FoundView::of).toList();
    }

    @GetMapping("/watched")
    public List<Long> watched(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @SessionAttribute(SessionController.USER) String user) {
        return watches.watched(sheetId, user);
    }

    /** Starts or stops the signed-in user watching the person. */
    @PostMapping("/{id}/watch")
    public WatchView watch(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody WatchForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return new WatchView(id, watches.watch(user, sheetId, id, form.on()));
    }

    /** Every patron named on the sheet, in the order they were named. */
    @GetMapping("/patrons")
    public List<PatronView> patrons(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.patrons(sheetId).stream().map(PatronView::of).toList();
    }

    /** Names another person on the sheet as the person's patron; refused for themselves or one already named. */
    @PostMapping("/{id}/patrons")
    public PatronView addPatron(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId,
            @PathVariable Long id, @Valid @RequestBody PatronForm form,
            @SessionAttribute(SessionController.USER) String user) {
        return PatronView.of(service.addPatron(user, sheetId, id, form.patronId()));
    }
}
