package com.androidtoolsuite.provider.shizuku;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class ShizukuTransportTest {
    @Test public void drainsAndRetainsBoundedTail() throws Exception {
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        assertArrayEquals("6789".getBytes(StandardCharsets.UTF_8), ShizukuTransport.tail(input, 4));
        assertEquals(0, input.available());
        assertArrayEquals(bytes, ShizukuTransport.tail(new ByteArrayInputStream(bytes), 20));
    }
}
