package dev.quant.os;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdBlockTest {

    @Test
    public void blocksExactHost() {
        assertTrue(AdBlock.blocked("doubleclick.net"));
    }

    @Test
    public void blocksSubdomain() {
        assertTrue(AdBlock.blocked("ads.doubleclick.net"));
    }

    @Test
    public void ignoresCase() {
        assertTrue(AdBlock.blocked("ADS.DoubleClick.NET"));
    }

    @Test
    public void allowsNearMiss() {
        assertFalse(AdBlock.blocked("notdoubleclick.net"));
        assertFalse(AdBlock.blocked("doubleclick.net.evil.com"));
    }

    @Test
    public void allowsNullAndEmpty() {
        assertFalse(AdBlock.blocked(null));
        assertFalse(AdBlock.blocked(""));
    }
}
