package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The own terms of the people on a sheet: their undated terms from the beginning of time, and any
 *  dated terms each in force from its from-date. */
@Service
public class PersonTermsService {

    private final PersonTermsRepository terms;

    public PersonTermsService(PersonTermsRepository terms) {
        this.terms = terms;
    }

    /** Each person's dated terms on the sheet, by person id. */
    public Map<Long, List<PersonTerms>> bySheet(String sheetId) {
        return terms.findBySheetId(sheetId).stream().collect(Collectors.groupingBy(PersonTerms::getPersonId));
    }

    /** The person's own terms in force on a date (null: none). */
    public Integer termsOn(Person person, LocalDate date) {
        return termsOn(person, terms.findByPersonId(person.getId()), date);
    }

    /** The person's own terms in force on a date among their dated terms: those with the latest
     *  from-date on or before it, else their undated terms (null: none). */
    public static Integer termsOn(Person person, List<PersonTerms> dated, LocalDate date) {
        return dated.stream()
                .filter(t -> !t.getFrom().isAfter(date))
                .max(Comparator.comparing(PersonTerms::getFrom))
                .map(t -> t.getTerms() == null ? Optional.<Integer>empty() : Optional.of(t.getTerms()))
                .orElseGet(() -> Optional.ofNullable(person.getTerms()))
                .orElse(null);
    }

    /** Record a person's own terms (null: none) from a date, replacing those recorded from that date
     *  and keeping the others. */
    public PersonTerms record(Person person, Integer value, LocalDate from) {
        PersonTerms recorded = terms.findByPersonIdAndFrom(person.getId(), from)
                .map(existing -> {
                    existing.setTerms(value);
                    return existing;
                })
                .orElseGet(() -> new PersonTerms(person.getSheetId(), person.getId(), value, from));
        return terms.saveAndFlush(recorded);
    }
}
