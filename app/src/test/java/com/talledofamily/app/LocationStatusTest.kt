package com.talledofamily.app
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
class LocationStatusTest {
 @Test fun recentLocationHasAge(){assertEquals("Reciente · hace 30s",locationAgeLabel("2026-01-01T00:00:00Z",Instant.parse("2026-01-01T00:00:30Z")))}
 @Test fun staleLocationIsNotCalledLive(){assertEquals("Última conocida · hace 10 min",locationAgeLabel("2026-01-01T00:00:00Z",Instant.parse("2026-01-01T00:10:00Z")))}
 @Test fun invalidTimestampDoesNotPretendLive(){assertEquals("Sin fecha válida",locationAgeLabel("invalid"))}
}
