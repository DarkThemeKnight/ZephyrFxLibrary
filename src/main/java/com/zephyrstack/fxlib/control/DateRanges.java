package com.zephyrstack.fxlib.control;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

import static java.time.DayOfWeek.MONDAY;

/**
 * Common, UI-friendly date presets.
 * Note: CUSTOM does not compute a range by itself — use DateRangePresets.custom(...)
 */
public enum DateRanges {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 days"),
    LAST_14_DAYS("Last 14 days"),
    LAST_30_DAYS("Last 30 days"),
    LAST_90_DAYS("Last 90 days"),
    THIS_WEEK("This week"),
    LAST_WEEK("Last week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_QUARTER("This quarter"),
    LAST_QUARTER("Last quarter"),
    YEAR_TO_DATE("Year to date"),
    ALL_TIME("All time"),
    CUSTOM("Custom");

    private final String label;
    DateRanges(String label) { this.label = label; }
    public String label() { return label; }

    /**
     * Compute a closed date range (inclusive) for this preset using the given clock and zone.
     * Weeks assume Monday as first day by default; override with DateRangePresets.of(..., firstDayOfWeek).
     * CUSTOM throws — supply your own range via DateRangePresets.custom(...).
     */
    public DateRangePresets.DateRange range(Clock clock, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return range(today, zone);
    }

    /**
     * Compute using an explicit 'today'. Useful for testing.
     */
    public DateRangePresets.DateRange range(LocalDate today, ZoneId zone) {
        return switch (this) {
            case TODAY -> DateRangePresets.of(today, today);
            case YESTERDAY -> {
                LocalDate d = today.minusDays(1);
                yield DateRangePresets.of(d, d);
            }
            case LAST_7_DAYS -> DateRangePresets.of(today.minusDays(6), today);
            case LAST_14_DAYS -> DateRangePresets.of(today.minusDays(13), today);
            case LAST_30_DAYS -> DateRangePresets.of(today.minusDays(29), today);
            case LAST_90_DAYS -> DateRangePresets.of(today.minusDays(89), today);

            case THIS_WEEK -> {
                LocalDate start = DateRangePresets.startOfWeek(today, MONDAY);
                LocalDate end = start.plusDays(6);
                yield DateRangePresets.of(start, end);
            }
            case LAST_WEEK -> {
                LocalDate start = DateRangePresets.startOfWeek(today, MONDAY).minusWeeks(1);
                LocalDate end = start.plusDays(6);
                yield DateRangePresets.of(start, end);
            }

            case THIS_MONTH -> {
                LocalDate start = today.with(TemporalAdjusters.firstDayOfMonth());
                LocalDate end = today.with(TemporalAdjusters.lastDayOfMonth());
                yield DateRangePresets.of(start, end);
            }
            case LAST_MONTH -> {
                LocalDate base = today.minusMonths(1);
                LocalDate start = base.with(TemporalAdjusters.firstDayOfMonth());
                LocalDate end = base.with(TemporalAdjusters.lastDayOfMonth());
                yield DateRangePresets.of(start, end);
            }

            case THIS_QUARTER -> {
                int qStartMonth = DateRangePresets.quarterStartMonth(today.getMonthValue());
                LocalDate start = LocalDate.of(today.getYear(), qStartMonth, 1);
                LocalDate end = start.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth());
                yield DateRangePresets.of(start, end);
            }
            case LAST_QUARTER -> {
                int qStartMonth = DateRangePresets.quarterStartMonth(today.getMonthValue());
                LocalDate start = LocalDate.of(today.getYear(), qStartMonth, 1).minusMonths(3);
                LocalDate end = start.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth());
                yield DateRangePresets.of(start, end);
            }

            case YEAR_TO_DATE -> {
                LocalDate start = LocalDate.of(today.getYear(), 1, 1);
                yield DateRangePresets.of(start, today);
            }

            case ALL_TIME -> {
                // Use a safe early anchor; keeps Instant conversion sane
                LocalDate start = LocalDate.of(1970, 1, 1);
                yield DateRangePresets.of(start, today);
            }

            case CUSTOM -> throw new UnsupportedOperationException(
                    "CUSTOM does not compute a range. Use DateRangePresets.custom(start, end).");
        };
    }
}
