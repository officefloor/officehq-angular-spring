package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.DatedSettings;
import net.officefloor.hq.app.settings.SheetSetting;

/** A sheet as it stood at the end of a date (see {@link SheetHistory#asOf}), that date standing in for
 * its today in every figure worked out from it. Its people and claims are detached copies: changing
 * them changes nothing in the store. */
public class SheetAsOf implements RollupService.Basis {

    private final LocalDate date;
    private final LocalDate policyDate;
    private final List<Person> people;
    private final List<Claim> claims;
    private final List<Rate> rates;
    private final List<SheetSetting> settings;

    SheetAsOf(LocalDate date, LocalDate policyDate, List<Person> people, List<Claim> claims, List<Rate> rates,
            List<SheetSetting> settings) {
        this.date = date;
        this.policyDate = policyDate;
        this.people = List.copyOf(people);
        this.claims = claims.stream()
                .sorted(Comparator.comparingLong(Claim::getListed).thenComparing(Claim::getId).reversed())
                .toList();
        this.rates = List.copyOf(rates);
        this.settings = List.copyOf(settings);
    }

    /** The person as they stood then; empty when they were not yet on the sheet. */
    public Optional<Person> person(long personId) {
        return people.stream().filter(p -> p.getId() == personId).findFirst();
    }

    /** The person's claims as they stood then, newest first. */
    public List<Claim> claimsOf(long personId) {
        return claims.stream().filter(c -> c.getPersonId() == personId).toList();
    }

    @Override
    public LocalDate today() {
        return date;
    }

    @Override
    public LocalDate policyDateInForce() {
        return policyDate == null ? date : policyDate;
    }

    @Override
    public Optional<Rate> rateInForce(String grade, LocalDate on) {
        return RateRepository.inForce(rates, grade, on);
    }

    @Override
    public BigDecimal floorOn(LocalDate on) {
        return valueOn(DatedSettings.FLOOR, on).map(BigDecimal::new).orElse(DatedSettings.FLOOR_START);
    }

    @Override
    public BigDecimal latePctOn(LocalDate on) {
        return valueOn(DatedSettings.LATE_PCT, on).map(BigDecimal::new).orElse(DatedSettings.LATE_PCT_START);
    }

    @Override
    public BigDecimal cap() {
        return valueOn(DatedSettings.CAP, date).map(BigDecimal::new).orElse(null);
    }

    @Override
    public Optional<Claim> claimNumbered(Long personId, String number) {
        return claims.stream()
                .filter(c -> c.getPersonId().equals(personId) && Objects.equals(c.getNumber(), number))
                .findFirst();
    }

    /** The key's value in force on the date (see {@link DatedSettings}); empty when none is recorded then,
     * or the one recorded is "none". */
    private Optional<String> valueOn(String key, LocalDate on) {
        return settings.stream()
                .filter(s -> s.getKey().equals(key) && !s.getFrom().isAfter(on))
                .max(Comparator.comparing(SheetSetting::getFrom))
                .map(SheetSetting::getValue);
    }
}
