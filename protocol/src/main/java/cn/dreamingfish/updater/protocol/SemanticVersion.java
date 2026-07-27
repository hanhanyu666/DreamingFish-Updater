package cn.dreamingfish.updater.protocol;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemanticVersion(int major, int minor, int patch, String preRelease)
        implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    );

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components must not be negative");
        }
    }

    public static SemanticVersion parse(String value) {
        if (value == null) {
            throw new ProtocolException("Semantic version is missing");
        }
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new ProtocolException("Invalid semantic version: " + value);
        }
        String preRelease = matcher.group(4);
        if (preRelease != null) {
            for (String identifier : preRelease.split("\\.")) {
                if (isNumeric(identifier) && identifier.length() > 1 && identifier.startsWith("0")) {
                    throw new ProtocolException(
                            "Numeric pre-release identifiers cannot contain leading zeroes: " + value);
                }
            }
        }
        try {
            return new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    preRelease
            );
        } catch (NumberFormatException e) {
            throw new ProtocolException("Semantic version component is too large: " + value, e);
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int core = Integer.compare(major, other.major);
        if (core == 0) core = Integer.compare(minor, other.minor);
        if (core == 0) core = Integer.compare(patch, other.patch);
        if (core != 0) return core;
        if (Objects.equals(preRelease, other.preRelease)) return 0;
        if (preRelease == null) return 1;
        if (other.preRelease == null) return -1;
        return comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.min(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String a = leftParts[i];
            String b = rightParts[i];
            boolean aNumeric = a.chars().allMatch(Character::isDigit);
            boolean bNumeric = b.chars().allMatch(Character::isDigit);
            int comparison;
            if (aNumeric && bNumeric) {
                comparison = Integer.compare(a.length(), b.length());
                if (comparison == 0) comparison = a.compareTo(b);
            } else if (aNumeric) {
                comparison = -1;
            } else if (bNumeric) {
                comparison = 1;
            } else {
                comparison = a.compareTo(b);
            }
            if (comparison != 0) return comparison;
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static boolean isNumeric(String value) {
        return value.chars().allMatch(Character::isDigit);
    }
}
