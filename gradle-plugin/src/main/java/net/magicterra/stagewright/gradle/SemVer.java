package net.magicterra.stagewright.gradle;

/** SemVer 2.0 precedence, as Fabric Loader applies it to a mod's declared dependency range. */
final class SemVer {

    private SemVer() {}

    /** Whether {@code version} is in {@code range}, written {@code [low,high)} as gradle/version.gradle writes it. */
    static boolean inRange(String version, String range) {
        if (!range.startsWith("[") || !range.endsWith(")") || range.indexOf(',') < 0) {
            throw new IllegalArgumentException("not a [low,high) range: " + range);
        }
        String low = range.substring(1, range.indexOf(','));
        String high = range.substring(range.indexOf(',') + 1, range.length() - 1);
        return compare(version, low) >= 0 && compare(version, high) < 0;
    }

    /** Negative, zero or positive as {@code a} precedes, equals or follows {@code b}; build metadata is ignored. */
    static int compare(String a, String b) {
        String[] x = split(a);
        String[] y = split(b);
        String[] xc = x[0].split("\\.");
        String[] yc = y[0].split("\\.");
        for (int i = 0; i < Math.max(xc.length, yc.length); i++) {
            long l = i < xc.length ? Long.parseLong(xc[i]) : 0;
            long r = i < yc.length ? Long.parseLong(yc[i]) : 0;
            if (l != r) return Long.compare(l, r);
        }
        // A pre-release precedes the release itself.
        if (x[1] == null || y[1] == null) return x[1] == null ? (y[1] == null ? 0 : 1) : -1;
        String[] xp = x[1].split("\\.");
        String[] yp = y[1].split("\\.");
        for (int i = 0; i < Math.min(xp.length, yp.length); i++) {
            int c = compareIdentifier(xp[i], yp[i]);
            if (c != 0) return c;
        }
        return Integer.compare(xp.length, yp.length);
    }

    /** Numeric identifiers by value and before alphanumeric ones, which compare as ASCII. */
    private static int compareIdentifier(String a, String b) {
        boolean an = a.chars().allMatch(Character::isDigit);
        boolean bn = b.chars().allMatch(Character::isDigit);
        if (an && bn) return Long.compare(Long.parseLong(a), Long.parseLong(b));
        if (an != bn) return an ? -1 : 1;
        return a.compareTo(b);
    }

    /** {core, pre-release or null}, the build metadata after {@code +} dropped. */
    private static String[] split(String version) {
        int plus = version.indexOf('+');
        String v = plus < 0 ? version : version.substring(0, plus);
        int dash = v.indexOf('-');
        return dash < 0 ? new String[] {v, null} : new String[] {v.substring(0, dash), v.substring(dash + 1)};
    }
}
