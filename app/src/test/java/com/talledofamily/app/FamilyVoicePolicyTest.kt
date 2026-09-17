package com.talledofamily.app
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class FamilyVoicePolicyTest {
    @Test fun backgroundVoiceRequiresAllOptInsUnlockedAndNotForeground(){
        assertTrue(canReadBackground(true,true,true,false,false))
        assertFalse(canReadBackground(false,true,true,false,false))
        assertFalse(canReadBackground(true,false,true,false,false))
        assertFalse(canReadBackground(true,true,false,false,false))
        assertFalse(canReadBackground(true,true,true,true,false))
        assertFalse(canReadBackground(true,true,true,false,true))
    }
    @Test fun nightQuietHoursWrapMidnight(){assertTrue(inQuietHours(23,22,7));assertTrue(inQuietHours(6,22,7));assertFalse(inQuietHours(7,22,7));assertFalse(inQuietHours(21,22,7))}
    @Test fun daytimeAndDisabledRanges(){assertTrue(inQuietHours(11,10,12));assertFalse(inQuietHours(12,10,12));assertFalse(inQuietHours(1,0,0))}
    @Test fun staleAndFutureGpsCannotClaimRecent(){val now=Instant.parse("2026-01-01T00:05:00Z");assertTrue(recentFamilyFix("2026-01-01T00:04:00Z",now));assertFalse(recentFamilyFix("2026-01-01T00:03:00Z",now));assertFalse(recentFamilyFix("2026-01-01T00:06:00Z",now));assertFalse(recentFamilyFix("bad",now))}
}
