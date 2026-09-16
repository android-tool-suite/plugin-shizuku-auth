package com.androidtoolsuite.provider.shizuku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class SystemLogSearchTest {
    @Test
    public void optionalTimeRangeUsesEpochAndLeavesAllLogsUnboundedByTime() {
        List<String> all = java.util.Arrays.asList(SystemLogSearch.logcatCommand(List.of("marker"), 0, 1_800_000_000_123L));
        assertFalse(all.contains("-T"));
        List<String> recent = java.util.Arrays.asList(SystemLogSearch.logcatCommand(List.of("marker"), 30, 1_800_000_000_123L));
        assertEquals("1799998200.123", recent.get(recent.indexOf("-T") + 1));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> SystemLogSearch.logcatCommand(List.of("marker"), -1, 0));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> SystemLogSearch.logcatCommand(List.of("marker"), 10081, 0));
    }
    @Test
    public void logcatPrefilterTreatsTermsAsLiteralText() {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(SystemLogSearch.logcatPattern(
                List.of("authkey=", "a.b[0]|other", "cost$5")
        ));
        assertTrue(pattern.matcher("prefix AUTHKEY=fixture").find());
        assertTrue(pattern.matcher("a.b[0]|other").find());
        assertTrue(pattern.matcher("cost$5").find());
        assertFalse(pattern.matcher("axb0").find());
        assertFalse(pattern.matcher("other").find());
    }

    @Test
    public void allTermsReturnsOnlyNewestBoundedMatches() {
        String log = "ordinary line\nAuthKey=old auth_appid=webview_gacha\n"
                + "AUTHKEY=new auth_appid=webview_gacha\nauthkey=other";
        SystemLogSearch.Result result = SystemLogSearch.search(
                log,
                List.of("authkey=", "auth_appid=webview_gacha"),
                true,
                1
        );
        assertEquals(List.of("AUTHKEY=new auth_appid=webview_gacha"), result.lines);
        assertTrue(result.truncated);
    }

    @Test
    public void anyTermIsCaseInsensitiveAndDoesNotReturnUnmatchedLines() {
        SystemLogSearch.Result result = SystemLogSearch.search(
                "Alpha\nBeta\nGamma",
                List.of("alpha", "GAMMA"),
                false,
                10
        );
        assertEquals(List.of("Alpha", "Gamma"), result.lines);
        assertFalse(result.truncated);
    }
}
