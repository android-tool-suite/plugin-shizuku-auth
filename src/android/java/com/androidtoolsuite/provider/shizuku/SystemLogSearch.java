package com.androidtoolsuite.provider.shizuku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class SystemLogSearch {
    private SystemLogSearch() {
    }

    static String[] logcatCommand(List<String> terms, int lookbackMinutes, long nowMillis) {
        if (lookbackMinutes < 0 || lookbackMinutes > 10080) {
            throw new IllegalArgumentException("lookbackMinutes must be 0-10080");
        }
        List<String> command = new ArrayList<>(List.of(
                "/system/bin/logcat", "-d", "-v", "raw", "-e", logcatPattern(terms)
        ));
        if (lookbackMinutes > 0) {
            long since = Math.max(0, nowMillis - lookbackMinutes * 60_000L);
            command.add("-T");
            command.add(String.format(Locale.ROOT, "%d.%03d", since / 1000, since % 1000));
        }
        return command.toArray(new String[0]);
    }

    /** Literal, case-insensitive alternation for logcat's ECMAScript regex (no shell). */
    static String logcatPattern(List<String> terms) {
        StringBuilder pattern = new StringBuilder();
        for (String term : terms) {
            if (pattern.length() > 0) pattern.append('|');
            for (char c : term.toCharArray()) {
                char lower = Character.toLowerCase(c), upper = Character.toUpperCase(c);
                if (lower != upper) {
                    pattern.append('[').append(lower).append(upper).append(']');
                } else {
                    if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) pattern.append('\\');
                    pattern.append(c);
                }
            }
        }
        return pattern.toString();
    }

    static Result search(String log, List<String> terms, boolean matchAll, int maxLines) {
        List<String> normalized = new ArrayList<>();
        for (String term : terms) normalized.add(term.toLowerCase(Locale.ROOT));
        List<String> matches = new ArrayList<>();
        for (String line : log.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            boolean hit = matchAll
                    ? normalized.stream().allMatch(lower::contains)
                    : normalized.stream().anyMatch(lower::contains);
            if (hit) matches.add(line);
        }
        boolean truncated = matches.size() > maxLines;
        int start = Math.max(0, matches.size() - maxLines);
        return new Result(matches.subList(start, matches.size()), truncated);
    }

    static final class Result {
        final List<String> lines;
        final boolean truncated;

        Result(List<String> lines, boolean truncated) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.truncated = truncated;
        }
    }
}
