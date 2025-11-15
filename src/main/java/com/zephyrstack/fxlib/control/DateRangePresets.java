package com.zephyrstack.fxlib.control;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Utilities for computing and converting date ranges for filters and queries.
 */
public final class DateRangePresets {
    private DateRangePresets() {}

    // ---------- Core type ----------
    /** Closed date range [start, end], inclusive. */
    public record DateRange(LocalDate start, LocalDate end) {
        public DateRange {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (end.isBefore(start)) {
                // normalize by swapping rather than throwing — nicer for UI
                LocalDate tmp = start;
                start = end;
                end = tmp;
            }
        }

        /** Start-of-day instant in the given zone. */
        public Instant startInstant(ZoneId zone) {
            return start.atStartOfDay(zone).toInstant();
        }

        /** End-of-day instant (inclusive) in the given zone. */
        public Instant endInstant(ZoneId zone) {
            return end.atTime(LocalTime.MAX).atZone(zone).toInstant();
        }

        /** Expand to ZonedDateTime pair for exact UI display/debugging. */
        public ZonedDateTime startOfDay(ZoneId zone) {
            return start.atStartOfDay(zone);
        }
        public ZonedDateTime endOfDay(ZoneId zone) {
            return end.atTime(LocalTime.MAX).atZone(zone);
        }

        @Override public String toString() {
            return "[" + start + " .. " + end + "]";
        }
    }

    // ---------- Factories ----------
    public static DateRange of(LocalDate start, LocalDate end) { return new DateRange(start, end); }
    public static DateRange custom(LocalDate start, LocalDate end) { return of(start, end); }

    public static DateRange of(DateRanges preset, ZoneId zone) {
        return preset.range(Clock.system(zone), zone);
    }
    public static DateRange of(DateRanges preset, ZoneId zone, Clock clock) {
        return preset.range(clock, zone);
    }
    public static DateRange of(DateRanges preset) {
        ZoneId zone = ZoneId.systemDefault();
        return preset.range(Clock.system(zone), zone);
    }

    // ---------- Helpers ----------
    /** Start of week for a given day as first day (e.g., MONDAY). */
    public static LocalDate startOfWeek(LocalDate date, DayOfWeek firstDayOfWeek) {
        DayOfWeek dow = date.getDayOfWeek();
        int shift = (7 + dow.getValue() - firstDayOfWeek.getValue()) % 7;
        return date.minusDays(shift);
    }

    /** Start-of-week using locale default (e.g., Sunday in US, Monday in NG/most locales). */
    public static LocalDate startOfWeek(LocalDate date, Locale locale) {
        DayOfWeek first = WeekFields.of(locale).getFirstDayOfWeek();
        return startOfWeek(date, first);
    }

    /** Quarter's first month (1,4,7,10) for a given month-of-year (1..12). */
    public static int quarterStartMonth(int month) {
        int idx = (month - 1) / 3; // 0..3
        return (idx * 3) + 1;
    }

    /** First/last day of month helpers. */
    public static LocalDate firstDayOfMonth(LocalDate d) {
        return d.with(TemporalAdjusters.firstDayOfMonth());
    }
    public static LocalDate lastDayOfMonth(LocalDate d) {
        return d.with(TemporalAdjusters.lastDayOfMonth());
    }

    // ---------- UI-friendly preset map (stable order) ----------
    /**
     * Typical order for a filter dropdown.
     * Returns a LinkedHashMap to preserve insertion order.
     */
    public static Map<DateRanges, DateRange> quickRanges(ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        Map<DateRanges, DateRange> map = new LinkedHashMap<>();
        map.put(DateRanges.TODAY, of(today, today));
        map.put(DateRanges.YESTERDAY, of(today.minusDays(1), today.minusDays(1)));
        map.put(DateRanges.LAST_7_DAYS, of(today.minusDays(6), today));
        map.put(DateRanges.LAST_30_DAYS, of(today.minusDays(29), today));
        map.put(DateRanges.THIS_WEEK, of(DateRanges.THIS_WEEK, zone));
        map.put(DateRanges.LAST_WEEK, of(DateRanges.LAST_WEEK, zone));
        map.put(DateRanges.THIS_MONTH, of(DateRanges.THIS_MONTH, zone));
        map.put(DateRanges.LAST_MONTH, of(DateRanges.LAST_MONTH, zone));
        map.put(DateRanges.THIS_QUARTER, of(DateRanges.THIS_QUARTER, zone));
        map.put(DateRanges.YEAR_TO_DATE, of(DateRanges.YEAR_TO_DATE, zone));
        map.put(DateRanges.ALL_TIME, of(DateRanges.ALL_TIME, zone));
        return map;
    }

    // ---------- Query helpers ----------
    /** Format as ISO-8601 strings for API query params (inclusive). */
    public static Map<String, String> toIsoDateParams(DateRange r, String fromKey, String toKey) {
        return Map.of(fromKey, r.start().toString(), toKey, r.end().toString());
    }

    /** Format as Instant timestamps for APIs that expect UTC instants. */
    public static Map<String, String> toInstantParams(DateRange r, ZoneId zone, String fromKey, String toKey) {
        return Map.of(fromKey, r.startInstant(zone).toString(), toKey, r.endInstant(zone).toString());
    }
}
