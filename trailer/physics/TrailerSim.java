import dev.shrimpscript.tumble.physics.Aabb;
import dev.shrimpscript.tumble.physics.RigidBody;
import dev.shrimpscript.tumble.physics.WorldCollider;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import dev.shrimpscript.tumble.ragdoll.LimbPose;
import dev.shrimpscript.tumble.ragdoll.RagdollSkeleton;
import org.joml.Vector3d;

import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drives Tumble's own ragdoll solver, unchanged, against the real trailer world.
 *
 * <p>Everything the mod does server side for a ragdoll is reproduced with its default
 * config: the skeleton is built from the player's pose, launched with the trigger's
 * velocity, stepped at 20 ticks per second, and impact damage is read from the same
 * speed-loss measure with the same threshold, multiplier and cooldown.
 *
 * <pre>
 *   java TrailerSim run    grid_dir scenario.txt out_prefix
 *   java TrailerSim search grid_dir scenario.txt     (sweeps the blast and prints a table)
 * </pre>
 */
public final class TrailerSim {

    // Defaults from TumbleConfig.
    static final double EXPLOSION_LAUNCH_MULTIPLIER = 10.0;
    static final double EXPLOSION_RADIUS_PADDING = 2.0;
    static final double IMPACT_THRESHOLD = 12.0;
    static final double IMPACT_MULTIPLIER = 0.75;
    static final double IMPACT_MAX = 20.0;
    static final int IMPACT_COOLDOWN = 10;
    static final double MAX_LAUNCH = 128.0;
    static final double GRAB_STRENGTH = 8.0;
    static final double ROLL_SPEED = 1.5;
    static final double ROLL_SPIN = 3.0;

    static byte[] grid;
    static int gx0, gy0, gz0, sx, sy, sz;

    static boolean solid(int x, int y, int z) {
        int lx = x - gx0, ly = y - gy0, lz = z - gz0;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) {
            return false;
        }
        return grid[(lx * sy + ly) * sz + lz] != 0;
    }

    static final WorldCollider COLLIDER = (q, out) -> {
        int x0 = (int) Math.floor(q.minX()), x1 = (int) Math.floor(q.maxX());
        int y0 = (int) Math.floor(q.minY()), y1 = (int) Math.floor(q.maxY());
        int z0 = (int) Math.floor(q.minZ()), z1 = (int) Math.floor(q.maxZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (solid(x, y, z)) {
                        out.add(Aabb.ofBlock(x, y, z));
                    }
                }
            }
        }
    };

    static void loadGrid(String dir) throws IOException {
        grid = Files.readAllBytes(Path.of(dir, "collision.u8"));
        String meta = Files.readString(Path.of(dir, "collision.json"));
        int[] o = ints(between(meta, "\"origin\": [", "]"));
        int[] s = ints(between(meta, "\"size\": [", "]"));
        gx0 = o[0]; gy0 = o[1]; gz0 = o[2];
        sx = s[0]; sy = s[1]; sz = s[2];
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a) + a.length();
        return s.substring(i, s.indexOf(b, i));
    }

    static int[] ints(String csv) {
        String[] p = csv.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            r[i] = Integer.parseInt(p[i].trim());
        }
        return r;
    }

    static double[] doubles(String csv) {
        String[] p = csv.split(",");
        double[] r = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            r[i] = Double.parseDouble(p[i].trim());
        }
        return r;
    }

    static Map<String, String> readScenario(String file) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            // Repeated keys (events) accumulate.
            m.merge(k, v, (a, b) -> a + ";" + b);
        }
        return m;
    }

    /** One simulated ragdoll, with the mod's per-tick bookkeeping. */
    static final class Run {
        final RagdollSkeleton skeleton;
        final List<float[]> frames = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int cooldown;
        double damageTaken;
        double health;
        int deathTick = -1;
        int impacts;
        double minY = 1e9;
        int airTicks;
        final List<double[]> handTrace = new ArrayList<>();

        Run(double[] feet, double yaw, LimbPose pose, double health) {
            skeleton = new RagdollSkeleton(feet[0], feet[1], feet[2],
                    RagdollSkeleton.facingFromYaw(yaw), true, pose);
            skeleton.setCollider(COLLIDER);
            skeleton.world().maxSpeed = MAX_LAUNCH;
            this.health = health;
        }

        void launch(double[] v) {
            Vector3d vel = new Vector3d(v[0], v[1], v[2]);
            if (vel.length() > MAX_LAUNCH) {
                vel.normalize(MAX_LAUNCH);
            }
            skeleton.launch(vel);
        }

        void record() {
            float[] f = new float[BodyPart.values().length * 7];
            int i = 0;
            for (BodyPart part : BodyPart.values()) {
                RigidBody b = skeleton.part(part);
                f[i++] = (float) b.pos.x;
                f[i++] = (float) b.pos.y;
                f[i++] = (float) b.pos.z;
                f[i++] = (float) b.rot.x;
                f[i++] = (float) b.rot.y;
                f[i++] = (float) b.rot.z;
                f[i++] = (float) b.rot.w;
            }
            frames.add(f);
        }

        void tick(int t) {
            skeleton.tick();
            double impact = skeleton.lastImpact();
            if (cooldown > 0) {
                cooldown--;
            } else if (impact >= IMPACT_THRESHOLD) {
                double dmg = Math.min((impact - IMPACT_THRESHOLD) * IMPACT_MULTIPLIER, IMPACT_MAX);
                if (dmg > 0) {
                    damageTaken += dmg;
                    health -= dmg;
                    impacts++;
                    cooldown = IMPACT_COOLDOWN;
                    Vector3d c = skeleton.centreOfMass(new Vector3d());
                    events.add(String.format(Locale.ROOT, "%d hurt %.2f impact %.2f at %.2f %.2f %.2f health %.2f",
                            t, dmg, impact, c.x, c.y, c.z, health));
                    if (health <= 0 && deathTick < 0) {
                        deathTick = t;
                        events.add(t + " death");
                    }
                }
            } else if (impact >= 4.0) {
                Vector3d c = skeleton.centreOfMass(new Vector3d());
                events.add(String.format(Locale.ROOT, "%d bump impact %.2f at %.2f %.2f %.2f", t, impact, c.x, c.y, c.z));
            }
            Vector3d c = skeleton.centreOfMass(new Vector3d());
            minY = Math.min(minY, c.y);
            if (!skeleton.isGrounded()) {
                airTicks++;
            }
            record();
        }
    }

    static LimbPose pose(Map<String, String> sc) {
        if (!sc.containsKey("pose")) {
            return LimbPose.STANDING;
        }
        double[] p = doubles(sc.get("pose"));
        return new LimbPose(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p.length > 7 && p[7] > 0.5);
    }

    /** Vanilla creeper blast, as Tumble turns it into a launch: measured from the chest. */
    static double[] blast(double[] feet, double[] centre, double power) {
        double ox = feet[0] - centre[0], oy = feet[1] + 0.9 - centre[1], oz = feet[2] - centre[2];
        double d = Math.sqrt(ox * ox + oy * oy + oz * oz);
        double reach = power * 2.0 + EXPLOSION_RADIUS_PADDING;
        if (d > reach) {
            return new double[]{0, 0, 0};
        }
        double falloff = 1.0 - d / reach;
        double speed = EXPLOSION_LAUNCH_MULTIPLIER * power * falloff;
        return new double[]{ox / d * speed, oy / d * speed, oz / d * speed};
    }

    static void setSolid(int x, int y, int z, boolean v) {
        int lx = x - gx0, ly = y - gy0, lz = z - gz0;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) {
            return;
        }
        grid[(lx * sy + ly) * sz + lz] = (byte) (v ? 1 : 0);
    }

    static Run simulate(Map<String, String> sc) {
        // Blocks removed before the run (the creeper's crater): "carve = x,y,z;x,y,z".
        List<int[]> carved = new ArrayList<>();
        if (sc.containsKey("carve")) {
            for (String e : sc.get("carve").split(";")) {
                if (e.isBlank()) {
                    continue;
                }
                int[] p = ints(e);
                if (solid(p[0], p[1], p[2])) {
                    carved.add(p);
                    setSolid(p[0], p[1], p[2], false);
                }
            }
        }
        try {
            return simulateIn(sc);
        } finally {
            for (int[] p : carved) {
                setSolid(p[0], p[1], p[2], true);
            }
        }
    }

    static Run simulateIn(Map<String, String> sc) {
        double[] feet = doubles(sc.get("feet"));
        double yaw = Double.parseDouble(sc.getOrDefault("yaw", "0"));
        Run run = new Run(feet, yaw, pose(sc), Double.parseDouble(sc.getOrDefault("health", "20")));
        if (sc.containsKey("motion")) {
            run.launch(doubles(sc.get("motion")));
        }
        if (sc.containsKey("blast")) {
            double[] b = doubles(sc.get("blast"));
            double[] v = blast(feet, b, b.length > 3 ? b[3] : 3.0);
            run.events.add(String.format(Locale.ROOT, "0 launch %.3f %.3f %.3f", v[0], v[1], v[2]));
            run.launch(v);
        }
        if (sc.containsKey("launch")) {
            run.launch(doubles(sc.get("launch")));
        }
        // Timed impulses: "impulse = tick:vx,vy,vz".
        Map<Integer, double[]> impulses = new LinkedHashMap<>();
        if (sc.containsKey("impulse")) {
            for (String e : sc.get("impulse").split(";")) {
                String[] kv = e.split(":");
                impulses.put(Integer.parseInt(kv[0].trim()), doubles(kv[1]));
            }
        }
        // Grab: "grab = part,startTick,endTick" with a hand path "hand = tick:x,y,z;..."
        int grabPart = -1, grabStart = 0, grabEnd = 0;
        List<double[]> hand = new ArrayList<>();
        if (sc.containsKey("grab")) {
            double[] g = doubles(sc.get("grab"));
            grabPart = (int) g[0];
            grabStart = (int) g[1];
            grabEnd = (int) g[2];
            for (String e : sc.get("hand").split(";")) {
                String[] kv = e.split(":");
                double[] p = doubles(kv[1]);
                hand.add(new double[]{Double.parseDouble(kv[0].trim()), p[0], p[1], p[2]});
            }
        }
        // Roll input (RagdollEntity.applyRollInput): "roll = startTick,endTick,dirX,dirZ".
        double[] roll = sc.containsKey("roll") ? doubles(sc.get("roll")) : null;
        int ticks = Integer.parseInt(sc.getOrDefault("ticks", "200"));
        run.record();
        for (int t = 1; t <= ticks; t++) {
            if (roll != null && t >= roll[0] && t <= roll[1]) {
                Vector3d direction = new Vector3d(roll[2], 0.0, roll[3]).normalize();
                double step = ROLL_SPEED / 20.0;
                Vector3d axis = new Vector3d(0.0, 1.0, 0.0).cross(direction);
                for (BodyPart part : BodyPart.values()) {
                    RigidBody body = run.skeleton.part(part);
                    body.pos.x += direction.x * step;
                    body.pos.z += direction.z * step;
                }
                run.skeleton.torso().angVel.fma(ROLL_SPIN, axis);
            }
            double[] imp = impulses.get(t);
            if (imp != null) {
                run.launch(imp);
                run.events.add(t + " impulse");
            }
            if (grabPart >= 0 && t >= grabStart && t <= grabEnd) {
                double[] h = handAt(hand, t);
                RigidBody body = run.skeleton.part(BodyPart.values()[grabPart]);
                // A player walks only as fast as the body follows: the hand stays within
                // grabLead of the part (and inside the mod's 2.5 block break distance).
                if (sc.containsKey("grabLead")) {
                    double lead = Double.parseDouble(sc.get("grabLead"));
                    double dx = h[0] - body.pos.x, dy = h[1] - body.pos.y, dz = h[2] - body.pos.z;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > lead) {
                        h = new double[]{body.pos.x + dx / d * lead, body.pos.y + dy / d * lead, body.pos.z + dz / d * lead};
                    }
                    run.handTrace.add(new double[]{t, h[0], h[1], h[2]});
                }
                body.linVel.set((h[0] - body.pos.x) * GRAB_STRENGTH, (h[1] - body.pos.y) * GRAB_STRENGTH,
                        (h[2] - body.pos.z) * GRAB_STRENGTH);
            }
            run.tick(t);
        }
        return run;
    }

    static double[] handAt(List<double[]> path, double t) {
        if (t <= path.get(0)[0]) {
            return new double[]{path.get(0)[1], path.get(0)[2], path.get(0)[3]};
        }
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1), b = path.get(i);
            if (t <= b[0]) {
                double u = (t - a[0]) / (b[0] - a[0]);
                return new double[]{a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u, a[3] + (b[3] - a[3]) * u};
            }
        }
        double[] l = path.get(path.size() - 1);
        return new double[]{l[1], l[2], l[3]};
    }

    static void write(Run run, String prefix) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(prefix + ".f32")))) {
            for (float[] f : run.frames) {
                for (float v : f) {
                    out.writeInt(Integer.reverseBytes(Float.floatToIntBits(v)));
                }
            }
        }
        try (PrintWriter w = new PrintWriter(prefix + ".events.txt")) {
            w.println("frames " + run.frames.size());
            for (String e : run.events) {
                w.println(e);
            }
            for (double[] h : run.handTrace) {
                w.println(String.format(Locale.ROOT, "%d hand at %.3f %.3f %.3f", (int) h[0], h[1], h[2], h[3]));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        loadGrid(args[1]);
        Map<String, String> sc = readScenario(args[2]);
        if (args[0].equals("run")) {
            Run run = simulate(sc);
            write(run, args[3]);
            System.out.printf(Locale.ROOT, "frames %d impacts %d damage %.1f minY %.2f death %d%n",
                    run.frames.size(), run.impacts, run.damageTaken, run.minY, run.deathTick);
            return;
        }
        // Search: sweep the creeper's position around the player and report each outcome.
        double[] feet = doubles(sc.get("feet"));
        double[] range = doubles(sc.getOrDefault("searchRange", "1.2,3.2,0.4"));
        int angles = Integer.parseInt(sc.getOrDefault("searchAngles", "24"));
        for (double dist = range[0]; dist <= range[1] + 1e-9; dist += range[2]) {
            for (int a = 0; a < angles; a++) {
                double ang = 2 * Math.PI * a / angles;
                double cx = feet[0] + Math.cos(ang) * dist, cz = feet[2] + Math.sin(ang) * dist;
                Map<String, String> v = new LinkedHashMap<>(sc);
                v.put("blast", String.format(Locale.ROOT, "%f,%f,%f,3", cx, feet[1], cz));
                Run run = simulate(v);
                float[] last = run.frames.get(run.frames.size() - 1);
                System.out.printf(Locale.ROOT, "dist %.2f ang %5.1f  creeper %.2f %.2f  impacts %d dmg %.1f minY %.1f end %.1f %.1f %.1f air %d death %d%n",
                        dist, Math.toDegrees(ang), cx, cz, run.impacts, run.damageTaken, run.minY,
                        last[7], last[8], last[9], run.airTicks, run.deathTick);
            }
        }
    }
}
