package io.github.jasonsimpart.modpack_update_checker;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

record PackVersion(List<BigInteger> parts, BigInteger test) implements Comparable<PackVersion> {
    private static final Pattern TAG = Pattern.compile("^v(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)(?:-test(\\d+))?$");
    private static final Pattern BUILD = Pattern.compile("^v\\d+\\.\\d+\\.\\d+\\.\\d+-test-build-\\d+$");

    static Optional<PackVersion> parse(String text) {
        var match = TAG.matcher(text);
        if (!match.matches()) return Optional.empty();
        return Optional.of(new PackVersion(List.of(new BigInteger(match.group(1)),
                new BigInteger(match.group(2)), new BigInteger(match.group(3)), new BigInteger(match.group(4))),
                match.group(5) == null ? null : new BigInteger(match.group(5))));
    }

    static boolean isDevelopment(String text) { return BUILD.matcher(text).matches(); }

    @Override
    public int compareTo(PackVersion other) {
        for (int i = 0; i < 4; i++) {
            int order = parts.get(i).compareTo(other.parts.get(i));
            if (order != 0) return order;
        }
        if (test == null) return other.test == null ? 0 : 1;
        return other.test == null ? -1 : test.compareTo(other.test);
    }
}
