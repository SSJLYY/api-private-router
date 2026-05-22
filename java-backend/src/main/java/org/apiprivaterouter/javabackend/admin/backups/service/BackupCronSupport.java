package org.apiprivaterouter.javabackend.admin.backups.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class BackupCronSupport {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public Instant computeNextRun(String cronExpression, Instant from) {
        CronExpression cron = CronExpression.parse(cronExpression);
        ZonedDateTime current = ZonedDateTime.ofInstant(from, DEFAULT_ZONE).withSecond(0).withNano(0);
        for (int i = 0; i < 525600; i++) {
            current = current.plusMinutes(1);
            if (cron.matches(current)) {
                return current.toInstant();
            }
        }
        throw new IllegalArgumentException("invalid cron expression");
    }

    public void validate(String cronExpression) {
        CronExpression.parse(cronExpression);
    }

    private record CronExpression(
            Field minutes,
            Field hours,
            Field daysOfMonth,
            Field months,
            Field daysOfWeek
    ) {
        private static CronExpression parse(String expression) {
            if (expression == null) {
                throw new IllegalArgumentException("invalid cron expression");
            }
            String[] parts = expression.trim().split("\\s+");
            if (parts.length != 5) {
                throw new IllegalArgumentException("invalid cron expression");
            }
            return new CronExpression(
                    Field.parse(parts[0], 0, 59),
                    Field.parse(parts[1], 0, 23),
                    Field.parse(parts[2], 1, 31),
                    Field.parse(parts[3], 1, 12),
                    Field.parse(parts[4], 0, 6, true)
            );
        }

        private boolean matches(ZonedDateTime time) {
            int dayOfWeek = time.getDayOfWeek().getValue() % 7;
            return minutes.matches(time.getMinute())
                    && hours.matches(time.getHour())
                    && daysOfMonth.matches(time.getDayOfMonth())
                    && months.matches(time.getMonthValue())
                    && daysOfWeek.matches(dayOfWeek);
        }
    }

    private record Field(Set<Integer> allowed) {
        private static Field parse(String token, int min, int max) {
            return parse(token, min, max, false);
        }

        private static Field parse(String token, int min, int max, boolean normalizeDow) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("invalid cron expression");
            }
            List<Integer> values = new ArrayList<>();
            for (String part : token.split(",")) {
                parsePart(part.trim(), min, max, normalizeDow, values);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("invalid cron expression");
            }
            return new Field(Set.copyOf(values));
        }

        private static void parsePart(String part, int min, int max, boolean normalizeDow, List<Integer> values) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("invalid cron expression");
            }
            String rangePart = part;
            int step = 1;
            if (part.contains("/")) {
                String[] pair = part.split("/", 2);
                if (pair.length != 2) {
                    throw new IllegalArgumentException("invalid cron expression");
                }
                rangePart = pair[0];
                step = parseNumber(pair[1], 1, max - min + 1, normalizeDow);
            }
            if ("*".equals(rangePart) || rangePart.isEmpty()) {
                addRange(values, min, max, step);
                return;
            }
            if (rangePart.contains("-")) {
                String[] bounds = rangePart.split("-", 2);
                if (bounds.length != 2) {
                    throw new IllegalArgumentException("invalid cron expression");
                }
                int start = parseNumber(bounds[0], min, max, normalizeDow);
                int end = parseNumber(bounds[1], min, max, normalizeDow);
                if (end < start) {
                    throw new IllegalArgumentException("invalid cron expression");
                }
                addRange(values, start, end, step);
                return;
            }
            values.add(parseNumber(rangePart, min, max, normalizeDow));
        }

        private static void addRange(List<Integer> values, int start, int end, int step) {
            for (int i = start; i <= end; i += step) {
                values.add(i);
            }
        }

        private static int parseNumber(String token, int min, int max, boolean normalizeDow) {
            try {
                int value = Integer.parseInt(token);
                if (normalizeDow && value == 7) {
                    value = 0;
                }
                if (value < min || value > max) {
                    throw new IllegalArgumentException("invalid cron expression");
                }
                return value;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid cron expression");
            }
        }

        private boolean matches(int value) {
            return allowed.contains(value);
        }
    }
}
