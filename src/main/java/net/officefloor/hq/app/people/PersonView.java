package net.officefloor.hq.app.people;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A person as the API returns it; patrons are referenced by id. A blank alpha, beta or gamma counts as
 * zero in every calculation. thrust is alpha times beta. momentum is thrust plus three times (gamma
 * minus alpha). scaled is momentum times two, and third is scaled divided by three (rounded
 * to the nearest, see {@link SheetMath#divide}); both null (n/a) when momentum is. norm is scaled minus the
 * sheet's average scaled (over the people not on hold whose scaled is not n/a, rounded like every divide), null
 * (n/a) when scaled is. backing is the momentum of each patron added up (patronage is mutual, see {@link #mutualPatrons}) (n/a patrons add nothing), null
 * (n/a) when the person's own momentum is. quarter is scaled divided by four (rounded like every divide)
 * and tier is SINK when scaled is below zero, STEADY from 0 up to 199 and SURGE at 200 or more; both
 * null (n/a) when scaled is. keel is twice scaled plus twice momentum plus twice norm plus twice
 * thrust, null (n/a) when momentum or norm is.
 * fathom is keel plus momentum plus 68 and draft is keel plus thrust plus 88; both null (n/a) when keel is.
 * cascade is fathom plus scaled plus 72, null (n/a) when fathom is. trim is keel minus thrust plus 92, null (n/a)
 * when keel is. ripple is cascade plus keel plus 76, null (n/a) when cascade is. heading is keel plus norm plus 96,
 * null (n/a) when keel or norm is. ballast is ripple plus thrust plus 80, null (n/a) when ripple is.
 * keystone is ballast plus norm plus 84, null (n/a) when ballast or norm is. bearing is keel minus norm plus 104, null
 * (n/a) when keel or norm is. pitch is keel plus momentum plus 108, null (n/a) when keel is. tackle is keel
 * minus momentum plus 112, null (n/a) when keel is. yaw is keel plus scaled plus 116, null (n/a) when keel is. heave
 * is keel minus scaled plus 120, null (n/a) when keel is. camber is keel plus 200 plus 124, null (n/a) when keel is.
 * sheer is keel minus 100 plus 128, null (n/a) when keel is. beam is keel times three plus 132, null (n/a) when keel is.
 * freeboard is keel divided by three (rounded like every divide) plus 136, null (n/a) when keel is. buoyancy is
 * keel times three plus 140, null (n/a) when keel is. knot is keel divided by three (rounded like every
 * divide) plus 144, null (n/a) when keel is. cable is keel plus gamma (a blank gamma counting as zero) plus 152, null
 * (n/a) when keel is. hull is keel minus gamma (a blank gamma counting as zero), null (n/a) when keel is.
 * bow is keel plus fathom, null (n/a) when keel is. stern is keel minus fathom, null (n/a) when keel is.
 * helm is keel plus ripple, null (n/a) when keel or ripple is. rudder is keel minus ripple, null (n/a)
 * when keel or ripple is. mast is keel plus 2000, null (n/a) when keel is. rigging is keel divided by
 * four (rounded like every divide), null (n/a) when keel is. moor is keel plus keystone, null (n/a)
 * when keel or keystone is. berth is keel minus keystone, null (n/a) when keel or keystone is. wharf is
 * keel times five, null (n/a) when keel is. jetty is keel divided by ten (rounded like every divide),
 * null (n/a) when keel is. pier is keel plus 500, null (n/a) when keel is. ebb is keel minus 500, null
 * (n/a) when keel is. flood is keel plus ballast, null (n/a) when keel or ballast is. swell is keel
 * minus ballast, null (n/a) when keel or ballast is.
 */
public record PersonView(Long id, String name, Integer alpha, Integer beta, Integer gamma, boolean hold,
        List<Long> patronIds, Long thrust, Long momentum, Long scaled, Long third, Long norm,
        Long backing, Long quarter, String tier, Long keel,
        Long fathom, Long draft, Long cascade, Long trim, Long ripple, Long heading, Long ballast,
        Long keystone, Long bearing, Long pitch, Long tackle, Long yaw, Long heave, Long camber,
        Long sheer, Long beam, Long freeboard, Long buoyancy, Long knot, Long cable,
        Long hull, Long bow, Long stern, Long helm, Long rudder, Long mast, Long rigging, Long moor,
        Long berth, Long wharf, Long jetty, Long pier, Long ebb,
        Long flood, Long swell) {

    static PersonView of(Person p, Set<Person> patrons, Long averageScaled) {
        return new PersonView(p.getId(), p.getName(), p.getAlpha(), p.getBeta(), p.getGamma(), p.isHold(),
                patrons.stream().map(Person::getId).sorted().toList(), thrust(p), momentum(p),
                scaled(p), third(p), norm(p, averageScaled), backing(p, patrons),
                quarter(p), tier(p), keel(p, averageScaled), fathom(p, averageScaled),
                draft(p, averageScaled), cascade(p, averageScaled), trim(p, averageScaled),
                ripple(p, averageScaled), heading(p, averageScaled), ballast(p, averageScaled),
                keystone(p, averageScaled), bearing(p, averageScaled), pitch(p, averageScaled),
                tackle(p, averageScaled), yaw(p, averageScaled), heave(p, averageScaled),
                camber(p, averageScaled), sheer(p, averageScaled), beam(p, averageScaled),
                freeboard(p, averageScaled), buoyancy(p, averageScaled), knot(p, averageScaled), cable(p, averageScaled),
                hull(p, averageScaled), bow(p, averageScaled), stern(p, averageScaled),
                helm(p, averageScaled), rudder(p, averageScaled), mast(p, averageScaled),
                rigging(p, averageScaled), moor(p, averageScaled), berth(p, averageScaled),
                wharf(p, averageScaled), jetty(p, averageScaled), pier(p, averageScaled),
                ebb(p, averageScaled), flood(p, averageScaled), swell(p, averageScaled));
    }

    /** The average scaled across the people, skipping n/a and people on hold; null when nobody has one. */
    static Long averageScaled(List<Person> people) {
        List<Long> values = people.stream().filter(p -> !p.isHold()).map(PersonView::scaled)
                .filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        long sum = values.stream().mapToLong(Long::longValue).sum();
        return SheetMath.divide(sum, values.size());
    }

    private static Long norm(Person p, Long averageScaled) {
        Long scaled = scaled(p);
        return scaled == null || averageScaled == null ? null : scaled - averageScaled;
    }

    /**
     * Patronage is mutual: each person's patrons are the people they name as patrons and the people
     * who name them, each counted once.
     */
    static Map<Long, Set<Person>> mutualPatrons(List<Person> people) {
        Map<Long, Set<Person>> patrons = new HashMap<>();
        for (Person p : people) {
            patrons.computeIfAbsent(p.getId(), id -> new LinkedHashSet<>());
            for (Person patron : p.getPatrons()) {
                if (patron.getId().equals(p.getId())) {
                    continue;
                }
                patrons.get(p.getId()).add(patron);
                patrons.computeIfAbsent(patron.getId(), id -> new LinkedHashSet<>()).add(p);
            }
        }
        return patrons;
    }

    private static Long backing(Person p, Set<Person> patrons) {
        if (momentum(p) == null) {
            return null;
        }
        return patrons.stream().map(PersonView::momentum).filter(m -> m != null)
                .mapToLong(Long::longValue).sum();
    }

    private static Long thrust(Person p) {
        return orZero(p.getAlpha()) * orZero(p.getBeta());
    }

    private static Long momentum(Person p) {
        return thrust(p) + 3L * (orZero(p.getGamma()) - orZero(p.getAlpha()));
    }

    /** A blank number counts as zero. */
    private static long orZero(Integer value) {
        return value == null ? 0L : value;
    }

    private static Long scaled(Person p) {
        Long momentum = momentum(p);
        return momentum == null ? null : momentum * 2L;
    }

    private static Long third(Person p) {
        Long scaled = scaled(p);
        return scaled == null ? null : SheetMath.divide(scaled, 3L);
    }

    private static Long quarter(Person p) {
        Long scaled = scaled(p);
        return scaled == null ? null : SheetMath.divide(scaled, 4L);
    }

    private static Long keel(Person p, Long averageScaled) {
        Long momentum = momentum(p);
        Long norm = norm(p, averageScaled);
        return momentum == null || norm == null ? null : 2L * scaled(p) + 2L * momentum + 2L * norm + 2L * thrust(p);
    }

    private static Long fathom(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + momentum(p) + 68L;
    }

    private static Long draft(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + thrust(p) + 88L;
    }

    private static Long cascade(Person p, Long averageScaled) {
        Long fathom = fathom(p, averageScaled);
        return fathom == null ? null : fathom + scaled(p) + 72L;
    }

    private static Long trim(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - thrust(p) + 92L;
    }

    private static Long ripple(Person p, Long averageScaled) {
        Long cascade = cascade(p, averageScaled);
        return cascade == null ? null : cascade + keel(p, averageScaled) + 76L;
    }

    private static Long ballast(Person p, Long averageScaled) {
        Long ripple = ripple(p, averageScaled);
        return ripple == null ? null : ripple + thrust(p) + 80L;
    }

    private static Long heading(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long norm = norm(p, averageScaled);
        return keel == null || norm == null ? null : keel + norm + 96L;
    }

    private static Long keystone(Person p, Long averageScaled) {
        Long ballast = ballast(p, averageScaled);
        Long norm = norm(p, averageScaled);
        return ballast == null || norm == null ? null : ballast + norm + 84L;
    }

    private static Long bearing(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long norm = norm(p, averageScaled);
        return keel == null || norm == null ? null : keel - norm + 104L;
    }

    private static Long pitch(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + momentum(p) + 108L;
    }

    private static Long tackle(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - momentum(p) + 112L;
    }

    private static Long yaw(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + scaled(p) + 116L;
    }

    private static Long heave(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - scaled(p) + 120L;
    }

    private static Long camber(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + 200L + 124L;
    }

    private static Long sheer(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - 100L + 128L;
    }

    private static Long beam(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel * 3L + 132L;
    }

    private static Long freeboard(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : SheetMath.divide(keel, 3L) + 136L;
    }

    private static Long buoyancy(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel * 3L + 140L;
    }

    private static Long knot(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : SheetMath.divide(keel, 3L) + 144L;
    }

    private static Long cable(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + orZero(p.getGamma()) + 152L;
    }

    private static Long hull(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - orZero(p.getGamma());
    }

    private static Long bow(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + fathom(p, averageScaled);
    }

    private static Long stern(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - fathom(p, averageScaled);
    }

    private static Long helm(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long ripple = ripple(p, averageScaled);
        return keel == null || ripple == null ? null : keel + ripple;
    }

    private static Long rudder(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long ripple = ripple(p, averageScaled);
        return keel == null || ripple == null ? null : keel - ripple;
    }

    private static Long mast(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + 2000L;
    }

    private static Long rigging(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : SheetMath.divide(keel, 4L);
    }

    private static Long moor(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long keystone = keystone(p, averageScaled);
        return keel == null || keystone == null ? null : keel + keystone;
    }

    private static Long berth(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long keystone = keystone(p, averageScaled);
        return keel == null || keystone == null ? null : keel - keystone;
    }

    private static Long wharf(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel * 5L;
    }

    private static Long jetty(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : SheetMath.divide(keel, 10L);
    }

    private static Long pier(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel + 500L;
    }

    private static Long ebb(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        return keel == null ? null : keel - 500L;
    }

    private static Long flood(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long ballast = ballast(p, averageScaled);
        return keel == null || ballast == null ? null : keel + ballast;
    }

    private static Long swell(Person p, Long averageScaled) {
        Long keel = keel(p, averageScaled);
        Long ballast = ballast(p, averageScaled);
        return keel == null || ballast == null ? null : keel - ballast;
    }

    private static String tier(Person p) {
        Long scaled = scaled(p);
        if (scaled == null) {
            return null;
        }
        if (scaled < 0) {
            return "SINK";
        }
        return scaled < 200 ? "STEADY" : "SURGE";
    }
}
