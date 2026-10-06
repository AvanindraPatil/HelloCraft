package dev.hnmc.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fails if Layout.java drifts from protocol/hn_protocol.h (via protocol/build/layout.txt). */
class LayoutTest {
	static Path protocolBuild() { return Path.of("..", "protocol", "build").toAbsolutePath().normalize(); }

	@Test
	void layoutMatchesCpp() throws IOException {
		Path dump = protocolBuild().resolve("layout.txt");
		assertTrue(Files.exists(dump), "missing " + dump + " - run protocol\\build_java_deps.bat first");

		Map<String, Long> cpp = new HashMap<>();
		for (String line : Files.readAllLines(dump)) {
			if (line.isBlank()) continue;
			int eq = line.indexOf('=');
			cpp.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1).trim()));
		}

		// Every key C++ prints must exist in Java with the same value, and vice versa.
		for (Map.Entry<String, Long> e : cpp.entrySet()) {
			assertEquals(e.getValue(), Layout.ALL.get(e.getKey()), "mismatch for " + e.getKey());
		}
		for (String key : Layout.ALL.keySet()) {
			assertTrue(cpp.containsKey(key), "Java has " + key + " but layout_dump does not print it");
		}
		System.out.println("layout: " + cpp.size() + " values identical between C++ and Java");
	}
}
