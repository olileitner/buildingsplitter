package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HouseNumberService {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^(\\d+)$");
    private static final Pattern NUMERIC_SUFFIX_PATTERN = Pattern.compile("^(\\d+)([A-Za-z])$");
    private static final Pattern LETTER_PATTERN = Pattern.compile("^([A-Za-z])$");

    public List<String> generateSequence(String startHouseNumber, int increment, int count) {
        if (count < 1) {
            throw new IllegalArgumentException(tr("Number of parts must be at least 1."));
        }
        if (increment == 0) {
            throw new IllegalArgumentException(tr("Increment cannot be 0."));
        }

        String start = startHouseNumber == null ? "" : startHouseNumber.trim();
        if (start.isEmpty()) {
            return List.of();
        }

        Matcher numericMatcher = NUMERIC_PATTERN.matcher(start);
        if (numericMatcher.matches()) {
            long base = Long.parseLong(numericMatcher.group(1));
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long value = base + (long) increment * i;
                values.add(Long.toString(value));
            }
            return values;
        }

        Matcher numericSuffixMatcher = NUMERIC_SUFFIX_PATTERN.matcher(start);
        if (numericSuffixMatcher.matches()) {
            String prefix = numericSuffixMatcher.group(1);
            char startLetter = numericSuffixMatcher.group(2).charAt(0);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                char shifted = shiftLetter(startLetter, increment * i);
                values.add(prefix + shifted);
            }
            return values;
        }

        Matcher letterMatcher = LETTER_PATTERN.matcher(start);
        if (letterMatcher.matches()) {
            char startLetter = letterMatcher.group(1).charAt(0);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                values.add(Character.toString(shiftLetter(startLetter, increment * i)));
            }
            return values;
        }

        throw new IllegalArgumentException(
            tr("Unsupported house number format. Use numeric (10), numeric+letter (10a), or letter (a).")
        );
    }

    private char shiftLetter(char base, int delta) {
        if (base >= 'a' && base <= 'z') {
            int shifted = base + delta;
            if (shifted < 'a' || shifted > 'z') {
                throw new IllegalArgumentException(tr("House number letter out of range (a-z)."));
            }
            return (char) shifted;
        }

        if (base >= 'A' && base <= 'Z') {
            int shifted = base + delta;
            if (shifted < 'A' || shifted > 'Z') {
                throw new IllegalArgumentException(tr("House number letter out of range (A-Z)."));
            }
            return (char) shifted;
        }

        throw new IllegalArgumentException(tr("Unsupported house number letter format."));
    }
}

