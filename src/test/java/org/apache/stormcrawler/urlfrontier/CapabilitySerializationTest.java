package org.apache.stormcrawler.urlfrontier;

import dev.onionall.brawler.archive.CapabilityArchiveStatusUpdaterBolt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Storm serializes unprepared graph components before any worker callback. */
public class CapabilitySerializationTest {
    @Test
    public void generatedComponentsSurviveStormSerializationAndUnpreparedCleanup() throws Exception {
        for (Object component : new Object[] {new CapabilitySpout(), new CapabilityStatusUpdaterBolt(),
                new CapabilityArchiveStatusUpdaterBolt()}) {
            var bytes = new ByteArrayOutputStream();
            try (var output = new ObjectOutputStream(bytes)) {
                output.writeObject(component);
            }
            Object restored;
            try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                restored = input.readObject();
            }
            assertEquals(component.getClass(), restored.getClass());
            if (restored instanceof CapabilityStatusUpdaterBolt updater) {
                updater.cleanup();
                updater.cleanup();
            }
        }
    }
}
