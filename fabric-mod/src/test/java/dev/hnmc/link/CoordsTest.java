package dev.hnmc.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoordsTest {
	@Test
	void positionRoundTripAndAxes() {
		double hx = 1234, hy = -567, hz = 890;
		double mx = Coords.mcX(hx), my = Coords.mcY(hz), mz = Coords.mcZ(hy);
		assertEquals(hx, Coords.hostX(mx), 1e-9);
		assertEquals(hy, Coords.hostY(mz), 1e-9);
		assertEquals(hz, Coords.hostZ(my), 1e-9);
		assertTrue(Coords.mcX(100) > 0 && Coords.mcZ(100) > 0 && Coords.mcY(100) > 0, "+X->+X, +Y->+Z, +Z->+Y");
	}

	@Test
	void yawMatchesDirectionVectors() {
		for (double hy = -180; hy <= 180; hy += 15) {
			double fx = Math.cos(Math.toRadians(hy)), fy = Math.sin(Math.toRadians(hy)); // host forward
			double mcFx = Coords.mcX(fx), mcFz = Coords.mcZ(fy);                          // same vector in MC axes
			double my = Math.toRadians(Coords.hostYawToMc(hy));
			assertEquals(mcFx, -Math.sin(my) / Coords.UNITS_PER_BLOCK, 1e-9, "x at yaw " + hy);
			assertEquals(mcFz, Math.cos(my) / Coords.UNITS_PER_BLOCK, 1e-9, "z at yaw " + hy);
			assertEquals(0.0, Math.IEEEremainder(Coords.mcYawToHost(Coords.hostYawToMc(hy)) - hy, 360.0), 1e-9);
		}
	}

	@Test
	void rightStaysRight() {
		// A/D must not swap: Unreal (left-handed) right = (-sin y, cos y); Minecraft right = forward x up = (-cos m, -sin m).
		for (double hy = -180; hy <= 180; hy += 15) {
			double rx = -Math.sin(Math.toRadians(hy)), ry = Math.cos(Math.toRadians(hy));
			double m = Math.toRadians(Coords.hostYawToMc(hy));
			assertEquals(-Math.cos(m) / Coords.UNITS_PER_BLOCK, Coords.mcX(rx), 1e-9, "right x at yaw " + hy);
			assertEquals(-Math.sin(m) / Coords.UNITS_PER_BLOCK, Coords.mcZ(ry), 1e-9, "right z at yaw " + hy);
		}
	}

	@Test
	void pitchFlipsSignAndNormalises() {
		assertEquals(-30.0, Coords.hostPitchToMc(30.0), 1e-9);      // looking up 30 in Unreal = -30 in Minecraft
		assertEquals(10.0, Coords.hostPitchToMc(350.0), 1e-9);      // Unreal reports -10 (down) as 350
		assertEquals(90.0, Coords.hostPitchToMc(-95.0), 1e-9);      // clamped
		assertEquals(0.0, Coords.hostPitchToMc(0.0), 1e-9);
	}
}
