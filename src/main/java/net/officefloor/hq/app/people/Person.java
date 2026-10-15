package net.officefloor.hq.app.people;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person on the list: a name and whole-number "alpha", "beta" and "gamma" (each absent until
 * given), and the derived "thrust" (alpha × beta), "drift" (gamma − alpha) and "momentum"
 * (thrust + 3 × drift), which are absent until all three raw values are given. A person may have
 * any number of "patrons": other people on the list, whose thrust added together is the person's
 * "backing". A person belongs to one sheet (null for the unnamed sheet) and is numbered within it, so sheets never share people. "Net" is momentum plus backing (backing counted as zero without patrons). A person may have a
 * "grade" (LOW, MID or HIGH; absent until given), from which their "weight" is 1, 3 or 5. "Load" is momentum times weight.
 * A person may have an "onset" date (absent until given); their "age" is the complete weeks from onset to the sheet's anchor.
 * "Decay" is momentum minus age. A person is "active" or not; a new person is active. "Clout" is the average of the
 * patrons' effective, rounded down (zero without patrons). "Influence" is effective plus clout.
 * A person may belong to any number of "regions": short codes typed in, like N1 or S2. A region's
 * "multiplier" comes from its first letter: N is 2, S is 3, any other is 1. "Reach" is the sum, over the
 * person's regions, of momentum times that region's multiplier. A person may follow any number of other people on
 * the list, without being followed back; their "allies" are the people they follow. "Synergy" is the
 * allies' thrust added together (zero without allies). "Combined" is influence plus synergy.
 * A person may have a whole-number "score" (absent until given); their "note sum" is the score (zero
 * without one) and their "quality" is the score (absent without one). */
@Entity
@Table(name = "person")
public class Person {

    /** The longest region code that can be stored. */
    public static final int REGION_MAX_LENGTH = 16;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The person's id within their sheet; the id the user and the API see. */
    @Column(name = "local_id")
    private Long localId;

    private String name;

    private Integer alpha;

    private Integer beta;

    private Integer gamma;

    @Enumerated(EnumType.STRING)
    private Grade grade;

    private LocalDate onset;

    private boolean active = true;

    /** The id of the sheet the person belongs to; null for the unnamed sheet. */
    @Column(name = "sheet_id")
    private String sheetId;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "person_patron", joinColumns = @JoinColumn(name = "person_id"),
            inverseJoinColumns = @JoinColumn(name = "patron_id"))
    private Set<Person> patrons = new HashSet<>();

    /** The people this person follows: their allies. Following is one-directional. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "person_follow", joinColumns = @JoinColumn(name = "follower_id"),
            inverseJoinColumns = @JoinColumn(name = "followed_id"))
    private Set<Person> follows = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_region", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "region")
    private Set<String> regions = new HashSet<>();

    private Integer score;

    protected Person() {
    }

    public Person(String sheetId, long localId, String name, Integer alpha, Integer beta, Integer gamma) {
        this.sheetId = sheetId;
        this.localId = localId;
        this.name = name;
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    public Long getId() {
        return id;
    }

    public Long getLocalId() {
        return localId;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    public Integer getAlpha() {
        return alpha;
    }

    public void setAlpha(Integer alpha) {
        this.alpha = alpha;
    }

    public Integer getBeta() {
        return beta;
    }

    public void setBeta(Integer beta) {
        this.beta = beta;
    }

    public Integer getGamma() {
        return gamma;
    }

    public void setGamma(Integer gamma) {
        this.gamma = gamma;
    }

    public Grade getGrade() {
        return grade;
    }

    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** The regions, sorted ascending. */
    public List<String> getRegions() {
        return regions.stream().sorted().toList();
    }

    public void setRegions(Collection<String> regions) {
        this.regions = new HashSet<>(regions);
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    /** The note sum is the score; zero without one. */
    public int getNoteSum() {
        return score == null ? 0 : score;
    }

    /** Quality is the score; absent without one. */
    public Integer getQuality() {
        return score;
    }

    /** Adjusted is combined plus quality (quality counted as zero without a score); absent while combined is. */
    public Integer getAdjusted() {
        Integer combined = getCombined();
        Integer quality = getQuality();
        return combined == null ? null : combined + (quality == null ? 0 : quality);
    }

    public LocalDate getOnset() {
        return onset;
    }

    /** Age is the complete seven-day weeks from onset to the anchor (leftover days dropped), negative when onset is
     * after it and truncated toward zero; absent without an onset. */
    public Long getAge(LocalDate anchor) {
        return onset == null ? null : ChronoUnit.WEEKS.between(onset, anchor);
    }

    /** Decay is momentum minus age; absent while either is absent. */
    public Long getDecay(LocalDate anchor) {
        Integer momentum = getMomentum();
        Long age = getAge(anchor);
        return momentum == null || age == null ? null : momentum - age;
    }

    /** Weight comes from the grade: LOW 1, MID 3, HIGH 5; absent without a grade. */
    public Integer getWeight() {
        return grade == null ? null : grade.weight();
    }

    /** The patrons, sorted by name. */
    public List<Person> getPatrons() {
        return patrons.stream()
                .sorted(Comparator.comparing(Person::getName).thenComparing(Person::getId))
                .toList();
    }

    public void setPatrons(Set<Person> patrons) {
        this.patrons = new HashSet<>(patrons);
    }

    /** The allies: the people this person follows, sorted by name. */
    public List<Person> getAllies() {
        return follows.stream()
                .sorted(Comparator.comparing(Person::getName).thenComparing(Person::getId))
                .toList();
    }

    /** Replaces the people this person follows; whether they follow this person back is unchanged. */
    public void setAllies(Set<Person> allies) {
        this.follows = new HashSet<>(allies);
    }

    /** The derived values exist only once alpha, beta and gamma are all given. */
    private boolean complete() {
        return alpha != null && beta != null && gamma != null;
    }

    /** Thrust is alpha multiplied by beta; absent until alpha, beta and gamma are all given. */
    public Integer getThrust() {
        return complete() ? alpha * beta : null;
    }

    /** Drift is gamma minus alpha; absent until alpha, beta and gamma are all given. */
    public Integer getDrift() {
        return complete() ? gamma - alpha : null;
    }

    /** Momentum is thrust plus three times drift; absent while either is absent. */
    public Integer getMomentum() {
        Integer thrust = getThrust();
        Integer drift = getDrift();
        return thrust == null || drift == null ? null : thrust + 3 * drift;
    }

    /** Load is momentum times weight; absent while either is absent. */
    public Integer getLoad() {
        Integer momentum = getMomentum();
        Integer weight = getWeight();
        return momentum == null || weight == null ? null : momentum * weight;
    }

    /** A region's multiplier from its first letter: N is 2, S is 3, any other is 1. */
    static int regionMultiplier(String region) {
        return switch (region.isEmpty() ? ' ' : Character.toUpperCase(region.charAt(0))) {
            case 'N' -> 2;
            case 'S' -> 3;
            default -> 1;
        };
    }

    /** Reach is the sum over the regions of momentum times the region's multiplier; absent without momentum or regions. */
    public Integer getReach() {
        Integer momentum = getMomentum();
        if (momentum == null || regions.isEmpty()) {
            return null;
        }
        return regions.stream().mapToInt(r -> momentum * regionMultiplier(r)).sum();
    }

    /** Backing is the sum of the patrons' thrust; absent without patrons or while any patron's thrust is absent. */
    public Integer getBacking() {
        if (patrons.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (Person patron : patrons) {
            Integer thrust = patron.getThrust();
            if (thrust == null) {
                return null;
            }
            sum += thrust;
        }
        return sum;
    }

    /** Synergy is the sum of the thrust of the people followed; zero without allies, absent while any ally's thrust is absent. */
    public Integer getSynergy() {
        int sum = 0;
        for (Person ally : getAllies()) {
            Integer thrust = ally.getThrust();
            if (thrust == null) {
                return null;
            }
            sum += thrust;
        }
        return sum;
    }

    /** Net is momentum plus backing, counting backing as zero without patrons; absent with momentum, or with a patron whose thrust is absent. */
    public Integer getNet() {
        Integer momentum = getMomentum();
        if (momentum == null) {
            return null;
        }
        if (patrons.isEmpty()) {
            return momentum;
        }
        Integer backing = getBacking();
        return backing == null ? null : momentum + backing;
    }

    /** Effective is net while active, and half of net rounded down while not active (absent while net is). */
    public Integer getEffective() {
        Integer net = getNet();
        if (net == null || active) {
            return net;
        }
        return Math.floorDiv(net, 2);
    }

    /** Clout is the average of the patrons' effective, rounded down; zero without patrons, absent while any patron's effective is absent. */
    public Integer getClout() {
        int sum = 0;
        for (Person patron : patrons) {
            Integer effective = patron.getEffective();
            if (effective == null) {
                return null;
            }
            sum += effective;
        }
        return patrons.isEmpty() ? 0 : Math.floorDiv(sum, patrons.size());
    }

    /** Influence is effective plus clout; absent while either is. */
    public Integer getInfluence() {
        Integer effective = getEffective();
        Integer clout = getClout();
        return effective == null || clout == null ? null : effective + clout;
    }

    /** Combined is influence plus synergy; absent while either is. */
    public Integer getCombined() {
        Integer influence = getInfluence();
        Integer synergy = getSynergy();
        return influence == null || synergy == null ? null : influence + synergy;
    }

    /** Tier classifies momentum: SINK below zero, STEADY up to 199, SURGE from 200; absent with momentum. */
    public String getTier() {
        Integer momentum = getMomentum();
        if (momentum == null) {
            return null;
        }
        return momentum < 0 ? "SINK" : momentum <= 199 ? "STEADY" : "SURGE";
    }

    /** Band classifies adjusted: E below 0, D up to 49, C up to 149, B up to 299, A from 300; absent with adjusted. */
    public String getBand() {
        Integer adjusted = getAdjusted();
        if (adjusted == null) {
            return null;
        }
        return adjusted < 0 ? "E" : adjusted <= 49 ? "D" : adjusted <= 149 ? "C" : adjusted <= 299 ? "B" : "A";
    }
}
