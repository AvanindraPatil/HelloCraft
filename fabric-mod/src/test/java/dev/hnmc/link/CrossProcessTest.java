package dev.hnmc.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 "done when": the real C++ fake host (protocol/tools/fake_host.cpp, a separate process)
 * drives our Java McLink and checks everything it sends back. Exit code 0 = all checks passed.
 */
class CrossProcessTest {
	@Test
	void cppFakeHostAgainstJavaLink() throws Exception {
		Path exe = LayoutTest.protocolBuild().resolve("fake_host.exe");
		assertTrue(Files.exists(exe), "missing " + exe + " - run protocol\\build_java_deps.bat first");

		String name = "Local\\HelloNeighborMC_cross_" + System.nanoTime();
		try (McLink link = new McLink(name)) {
			Process p = new ProcessBuilder(exe.toString(), name, "6").redirectErrorStream(true).start();
			List<String> output = new ArrayList<>();
			Thread pump = new Thread(() -> {
				try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
					String line;
					while ((line = r.readLine()) != null) synchronized (output) { output.add(line); }
				} catch (Exception ignored) { }
			});
			pump.start();

			// Tick at 20 Hz like Minecraft until the host process exits.
			long next = System.nanoTime();
			while (p.isAlive()) {
				link.tick();
				next += 50_000_000L;
				long sleep = next - System.nanoTime();
				if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
			}
			assertTrue(p.waitFor(10, TimeUnit.SECONDS));
			pump.join(2000);
			synchronized (output) { output.forEach(l -> System.out.println("  [fake_host] " + l)); }

			System.out.println("java link: " + link.ticks() + " ticks, " + link.commandsHandled() + " commands handled");
			assertEquals(0, p.exitValue(), "fake_host reported failures, see output above");
			assertTrue(link.commandsHandled() >= 5);
		}
	}
}
