package dev.hnmc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hnmc.link.Layout;
import dev.hnmc.link.McLink;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where Minecraft draws the held Hello Neighbor item, every frame, so HnLink can put the real Hello Neighbor object
 * exactly there (with Minecraft's walk bob, arm swing and body turn). The item itself is never drawn by Minecraft:
 * the mixins on Minecraft's first-person hand renderer and on the third-person ItemInHandLayer record the pose at the
 * moment the item would be submitted, then skip it.
 *
 * Both are sent RELATIVE (HnLink adds them to the camera / feet it applies in the same update, so the big motion
 * never lags; a pose from the previous frame next to the newest camera was the "one frame behind" stutter).
 * First person: Minecraft's hand renderer; its PoseStack starts with the inverse view rotation, so the translation is
 * a world-aligned offset from the camera; plus the item's rotation minus the camera's (kEvHeldRot, Unreal degrees;
 * zero at rest). Third person: only the pass HnEntities makes for Hello Neighbor counts (its PoseStack is relative to
 * the floored player position, set here while it runs): offset from the player's feet, body yaw, the arm's swing.
 */
public final class HnHeld {
	static McLink link;
	private static boolean capturing;
	private static double ox, oy, oz;
	private static boolean firstFresh, thirdFresh;
	private static double fx, fy, fz;
	private static float fPitch, fYaw, fRoll;
	private static double tx, ty, tz;
	private static float bodyYaw, armSwing;
	/** HumanoidModel's ITEM arm pose raises the arm by PI/10: that is the rest pose, no swing. */
	private static final float ITEM_ARM_REST = (float) (-Math.PI / 10);

	private HnHeld() {}

	/** HnEntities: its third-person pass starts / ends (origin = what its poses are relative to). */
	static void beginCapture(double x, double y, double z) { capturing = true; ox = x; oy = y; oz = z; }
	static void endCapture() { capturing = false; }

	/** Mixin, first person: the held item's pose. */
	public static void firstPerson(PoseStack pose) {
		Matrix4f m = pose.last().pose();
		net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
		fx = m.m30();
		fy = m.m31();
		fz = m.m32();
		// The item's forward (-Z) and up (+Y), Minecraft world -> Unreal (x, y, z) = (mx, mz, my), as a rotator.
		Vector3f f = m.transformDirection(new Vector3f(0, 0, -1)).normalize();
		Vector3f u = m.transformDirection(new Vector3f(0, 1, 0)).normalize();
		float Fx = f.x, Fy = f.z, Fz = f.y, Ux = u.x, Uy = u.z, Uz = u.y;
		double yaw = Math.atan2(Fy, Fx), pitch = Math.atan2(Fz, Math.sqrt(Fx * Fx + Fy * Fy));
		// Up of the same rotator without roll, and its right vector; roll = the turn of the real up between them.
		double u0x = -Math.sin(pitch) * Math.cos(yaw), u0y = -Math.sin(pitch) * Math.sin(yaw), u0z = Math.cos(pitch);
		double r0x = -Math.sin(yaw), r0y = Math.cos(yaw);
		double roll = Math.atan2(-(Ux * r0x + Uy * r0y), Ux * u0x + Uy * u0y + Uz * u0z);
		// Minus the camera's own rotator (Minecraft yaw + 90, pitch flipped): only bob / swing / use remain.
		fPitch = (float) Math.toDegrees(pitch) + camera.xRot();
		fYaw = (float) net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(yaw) - (camera.yRot() + 90.0));
		fRoll = (float) Math.toDegrees(roll);
		firstFresh = true;
	}

	/** Mixin, third person: the held item's pose in Steve's hand; armXRot = the arm's swing (radians). */
	public static void thirdPerson(PoseStack pose, double feetX, double feetY, double feetZ, float yaw, float armXRot) {
		if (!capturing) return;   // Minecraft's own (hidden) window draws the player too: not ours
		Matrix4f m = pose.last().pose();
		tx = ox + m.m30() - feetX;
		ty = oy + m.m31() - feetY;
		tz = oz + m.m32() - feetZ;
		bodyYaw = yaw;
		armSwing = armXRot - ITEM_ARM_REST;
		thirdFresh = true;
	}

	/** Render thread, end of every frame: send this frame's poses. */
	static void push() {
		if (link == null) return;
		if (firstFresh) {
			link.pushEvent(Layout.EV_HELD_FIRST, mm(fx), mm(fy), mm(fz), 0);
			link.pushEvent(Layout.EV_HELD_ROT, Math.round(fPitch * 100), Math.round(fYaw * 100), Math.round(fRoll * 100), 0);
		}
		if (thirdFresh) {
			int yaw10 = Math.floorMod(Math.round(bodyYaw * 10), 3600);
			int swing = Math.max(-32000, Math.min(32000, Math.round(armSwing * 1000)));
			link.pushEvent(Layout.EV_HELD_THIRD, mm(tx), mm(ty), mm(tz), yaw10 | (swing << 16));
		}
		firstFresh = thirdFresh = false;
	}

	private static int mm(double v) { return (int) Math.round(v * 1000); }
}
