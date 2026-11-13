package net.officefloor.hq.app.person;

import java.time.LocalDate;

/** A claim dated after its person's retirement date is refused. */
public class PersonRetiredException extends RuntimeException {

    public PersonRetiredException(String name, LocalDate from, LocalDate date) {
        super("A claim dated " + date + " falls after " + name + " retired from " + from);
    }
}
