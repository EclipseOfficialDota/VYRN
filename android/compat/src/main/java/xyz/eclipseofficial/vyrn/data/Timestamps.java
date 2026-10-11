package xyz.eclipseofficial.vyrn.data;

import java.time.Instant;
import java.time.OffsetDateTime;

/** API timestamps require an explicit offset, including PostgreSQL's +00:00. */
public final class Timestamps {
    private Timestamps() {}

    public static Instant parse(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }
}
