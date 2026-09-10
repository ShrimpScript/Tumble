package dev.shrimpscript.tumble.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Gameplay and physics values live on the SERVER spec, so a server dictates them to
 * every client. Presentation lives on the CLIENT spec.
 *
 * <p>Defaults here are starting points for tuning, not final values.
 */
public final class TumbleConfig {

    public static final Server SERVER;
    public static final ForgeConfigSpec SERVER_SPEC;

    public static final Client CLIENT;
    public static final ForgeConfigSpec CLIENT_SPEC;

    static {
        Pair<Server, ForgeConfigSpec> server = new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();

        Pair<Client, ForgeConfigSpec> client = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();
    }

    private TumbleConfig() {
    }

    public static final class Server {

        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.BooleanValue allowManualTrigger;
        public final ForgeConfigSpec.BooleanValue affectCreative;
        public final ForgeConfigSpec.IntValue cooldownTicks;
        public final ForgeConfigSpec.IntValue minGetUpTicks;
        public final ForgeConfigSpec.BooleanValue requireGroundedToGetUp;
        public final ForgeConfigSpec.IntValue groundedTicksBeforeGetUp;
        public final ForgeConfigSpec.DoubleValue maxLaunchSpeed;
        public final ForgeConfigSpec.BooleanValue partSelfCollision;
        public final ForgeConfigSpec.BooleanValue rollEnabled;
        public final ForgeConfigSpec.DoubleValue rollSpeed;
        public final ForgeConfigSpec.DoubleValue rollSpin;

        public final ForgeConfigSpec.BooleanValue grabEnabled;
        public final ForgeConfigSpec.DoubleValue grabReach;
        public final ForgeConfigSpec.DoubleValue grabHoldDistance;
        public final ForgeConfigSpec.DoubleValue grabStrength;
        public final ForgeConfigSpec.DoubleValue grabBreakDistance;
        public final ForgeConfigSpec.IntValue safetyTimeoutTicks;
        public final ForgeConfigSpec.BooleanValue expireWhenSlow;
        public final ForgeConfigSpec.DoubleValue releaseSpeedThreshold;

        public final ForgeConfigSpec.BooleanValue fallEnabled;
        public final ForgeConfigSpec.DoubleValue fallMinHeight;
        public final ForgeConfigSpec.DoubleValue fallMinDamage;
        public final ForgeConfigSpec.DoubleValue fallSlamMultiplier;

        public final ForgeConfigSpec.BooleanValue impactEnabled;
        public final ForgeConfigSpec.DoubleValue impactMinVelocityDelta;
        public final ForgeConfigSpec.DoubleValue impactMaxVelocityDelta;

        public final ForgeConfigSpec.BooleanValue hitEnabled;
        public final ForgeConfigSpec.DoubleValue hitMinDamage;
        public final ForgeConfigSpec.DoubleValue hitLaunchMultiplier;
        public final ForgeConfigSpec.DoubleValue hitProjectileLaunchMultiplier;
        public final ForgeConfigSpec.DoubleValue hitMaxLaunchSpeed;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> projectileDamageTypes;

        public final ForgeConfigSpec.BooleanValue explosionEnabled;
        public final ForgeConfigSpec.DoubleValue explosionMinPower;
        public final ForgeConfigSpec.DoubleValue explosionRadiusPadding;
        public final ForgeConfigSpec.DoubleValue explosionLaunchMultiplier;

        public final ForgeConfigSpec.BooleanValue lightningEnabled;
        public final ForgeConfigSpec.DoubleValue lightningLaunchSpeed;

        public final ForgeConfigSpec.BooleanValue crashEnabled;
        public final ForgeConfigSpec.DoubleValue crashMinDamage;
        public final ForgeConfigSpec.DoubleValue crashLaunchMultiplier;

        public final ForgeConfigSpec.IntValue suppressionGraceTicks;
        public final ForgeConfigSpec.BooleanValue suppressRiptide;
        public final ForgeConfigSpec.BooleanValue suppressBounce;
        public final ForgeConfigSpec.BooleanValue suppressElytraFlight;
        public final ForgeConfigSpec.BooleanValue suppressCreativeFlight;
        public final ForgeConfigSpec.BooleanValue suppressClimbing;
        public final ForgeConfigSpec.BooleanValue suppressWater;

        public final ForgeConfigSpec.BooleanValue impactDamageEnabled;
        public final ForgeConfigSpec.DoubleValue impactDamageThreshold;
        public final ForgeConfigSpec.DoubleValue impactDamageMultiplier;
        public final ForgeConfigSpec.DoubleValue impactDamageMax;
        public final ForgeConfigSpec.IntValue impactDamageCooldownTicks;

        public final ForgeConfigSpec.BooleanValue corpseEnabled;
        public final ForgeConfigSpec.IntValue corpseLifetimeTicks;
        public final ForgeConfigSpec.BooleanValue corpseDespawnWhenEmpty;
        public final ForgeConfigSpec.IntValue corpseFreezeTicks;

        public final ForgeConfigSpec.BooleanValue mobCorpseEnabled;
        public final ForgeConfigSpec.IntValue mobCorpseLifetimeTicks;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> mobCorpseTypes;

        public final ForgeConfigSpec.BooleanValue soundEnabled;
        public final ForgeConfigSpec.DoubleValue soundVolume;

        private Server(ForgeConfigSpec.Builder b) {
            b.comment("Ragdoll behaviour. Server-authoritative.").push("general");

            enabled = b
                    .comment("Master switch. When false, nothing ever ragdolls.")
                    .define("enabled", true);

            allowManualTrigger = b
                    .comment("Whether players may ragdoll themselves with the keybind.")
                    .define("allowManualTrigger", true);

            affectCreative = b
                    .comment("Whether players in creative mode can be ragdolled.")
                    .define("affectCreative", true);

            cooldownTicks = b
                    .comment("Ticks a player must wait between ragdolls.")
                    .defineInRange("cooldownTicks", 60, 0, 20 * 60);

            minGetUpTicks = b
                    .comment("Ticks a player must stay down before they may get up at all.")
                    .defineInRange("minGetUpTicks", 60, 0, 20 * 60);

            requireGroundedToGetUp = b
                    .comment("Whether a player must have actually landed before getting up.",
                            "Without this a player flung through the air can stand up mid-flight,",
                            "which cancels the whole point of being thrown.")
                    .define("requireGroundedToGetUp", true);

            groundedTicksBeforeGetUp = b
                    .comment("Ticks a body must stay on the ground before its player may rise.",
                            "Counted from the moment it lands, and reset if it is thrown clear",
                            "again, so a bouncing body does not qualify on a graze.")
                    .defineInRange("groundedTicksBeforeGetUp", 20, 0, 20 * 60);

            b.pop();
            b.comment("Physics limits.").push("physics");

            maxLaunchSpeed = b
                    .comment("Hard cap on launch speed, in blocks per second.",
                            "Prevents a large explosion from firing a body out of the world.")
                    .defineInRange("maxLaunchSpeed", 128.0D, 1.0D, 1024.0D);

            partSelfCollision = b
                    .comment("Whether limbs collide with each other.",
                            "Off is cheaper and lets a body settle flatter, but arms end up inside legs.")
                    .define("partSelfCollision", true);

            b.pop();
            b.comment("What a ragdolled player can still do.").push("controls");

            rollEnabled = b
                    .comment("Whether WASD rolls the body while down.")
                    .define("rollEnabled", true);

            rollSpeed = b
                    .comment("How fast a downed player can squirm, in blocks per second.",
                            "Walking is about 4.3, so keep this well below it or being downed",
                            "costs the player nothing.")
                    .defineInRange("rollSpeed", 1.5D, 0.0D, 10.0D);

            rollSpin = b
                    .comment("Spin added while rolling, in radians per second.",
                            "Cosmetic: it makes the body tumble rather than slide. Movement itself",
                            "comes from rollSpeed, because torque alone cannot roll a prone body.")
                    .defineInRange("rollSpin", 3.0D, 0.0D, 50.0D);

            b.pop();
            b.comment("Picking bodies up and dragging them around.").push("grab");

            grabEnabled = b
                    .comment("Whether players can grab a downed player, a corpse or a dead mob.")
                    .define("enabled", true);

            grabReach = b
                    .comment("How far away a body can be grabbed from, in blocks.")
                    .defineInRange("reach", 4.0D, 1.0D, 16.0D);

            grabHoldDistance = b
                    .comment("How far in front of the grabber the held limb is pulled to.")
                    .defineInRange("holdDistance", 2.0D, 0.5D, 8.0D);

            grabStrength = b
                    .comment("How hard the grip pulls, as a rate per second.",
                            "Higher is a firmer hold; too high and a body snaps about.")
                    .defineInRange("strength", 8.0D, 0.5D, 60.0D);

            grabBreakDistance = b
                    .comment("How far the held limb may lag behind the hand before the grip",
                            "breaks, in blocks. This is what stops a body being dragged through",
                            "a wall: the limb catches, falls behind, and the grip lets go.")
                    .defineInRange("breakDistance", 2.5D, 0.5D, 32.0D);

            b.pop();
            b.comment("When a ragdoll ends on its own.").push("expiry");

            safetyTimeoutTicks = b
                    .comment("Hard limit on how long a ragdoll can last, in ticks.",
                            "A backstop so a wedged body can never strand a player permanently.")
                    .defineInRange("safetyTimeoutTicks", 600, 20, 20 * 600);

            expireWhenSlow = b
                    .comment("Stand the player up automatically once the body stops moving.")
                    .define("expireWhenSlow", false);

            releaseSpeedThreshold = b
                    .comment("Speed below which the body counts as settled, in blocks per second.")
                    .defineInRange("releaseSpeedThreshold", 0.1D, 0.001D, 10.0D);

            b.pop();
            b.comment("What knocks a player down. Each trigger has its own threshold.").push("triggers");

            b.comment("Landing hard enough to hurt.").push("fall");
            fallEnabled = b.define("enabled", true);
            fallMinHeight = b
                    .comment("Blocks fallen at or above which the player goes down, whatever the",
                            "damage. Height and damage are separate conditions and either can fire:",
                            "damage alone misses a long fall softened by armour or feather falling,",
                            "and height alone misses a short drop onto something that hurts.",
                            "Set to 0 to ignore height and go by damage only.")
                    .defineInRange("minHeight", 6.0D, 0.0D, 1024.0D);

            fallMinDamage = b
                    .comment("Fall damage at or above which the player goes down.",
                            "Set to 0 to ignore damage and go by height only.")
                    .defineInRange("minDamage", 4.0D, 0.0D, 1024.0D);
            fallSlamMultiplier = b.comment("Share of the landing speed driven into the body.")
                    .defineInRange("slamMultiplier", 0.5D, 0.0D, 10.0D);
            b.pop();

            b.comment("Hitting something at speed, whatever the damage.").push("impact");
            impactEnabled = b.define("enabled", true);
            impactMinVelocityDelta = b
                    .comment("Change in speed, in blocks per second, that counts as an impact.")
                    .defineInRange("minVelocityDelta", 16.0D, 0.0D, 1024.0D);
            impactMaxVelocityDelta = b
                    .comment("Impacts above this are treated as this, so nothing is launched absurdly.")
                    .defineInRange("maxVelocityDelta", 120.0D, 0.0D, 1024.0D);
            b.pop();

            b.comment("Taking a heavy blow.").push("hit");
            hitEnabled = b.define("enabled", true);
            hitMinDamage = b.comment("Damage in a single hit at or above which the player goes down.")
                    .defineInRange("minDamage", 8.0D, 0.0D, 1024.0D);
            hitLaunchMultiplier = b
                    .comment("How hard the blow throws the body, multiplied by the damage dealt.",
                            "A 10 damage hit at the default throws the body at 15 blocks per second.")
                    .defineInRange("launchMultiplier", 1.5D, 0.0D, 100.0D);

            hitProjectileLaunchMultiplier = b
                    .comment("The same, for anything tagged as a projectile: arrows, and gun",
                            "mods that tag their damage properly.",
                            "A bullet carries very little momentum compared to the damage it does,",
                            "so being shot should drop someone where they stand rather than",
                            "launching them. Set equal to launchMultiplier to disable the",
                            "distinction.")
                    .defineInRange("projectileLaunchMultiplier", 0.15D, 0.0D, 100.0D);

            hitMaxLaunchSpeed = b
                    .comment("Hard ceiling on how fast any single blow can throw a body,",
                            "in blocks per second. This is the safety net: launch scales with",
                            "damage, and a modded weapon doing 30 damage would otherwise fling",
                            "a player at 45 blocks per second no matter what else is configured.")
                    .defineInRange("maxLaunchSpeed", 12.0D, 0.0D, 1024.0D);

            projectileDamageTypes = b
                    .comment("Extra damage types or damage type tags to treat as projectiles.",
                            "Gun mods define their own damage types and mostly do NOT join",
                            "minecraft:is_projectile, so the vanilla tag alone does not catch them.",
                            "Each entry may be either a damage type id or a tag id; both are tried.",
                            "Verified against the jars: TACZ uses the tag tacz:bullets, and",
                            "Superb Warfare uses superbwarfare:gun_damage and superbwarfare:projectile.")
                    .defineList("projectileDamageTypes",
                            java.util.List.of("tacz:bullets",
                                    "superbwarfare:gun_damage",
                                    "superbwarfare:projectile"),
                            entry -> entry instanceof String);
            b.pop();

            b.comment("Being caught in a blast.").push("explosion");
            explosionEnabled = b.define("enabled", true);
            explosionMinPower = b.comment("Explosions weaker than this are ignored. TNT is 4.")
                    .defineInRange("minPower", 1.0D, 0.0D, 1024.0D);
            explosionRadiusPadding = b
                    .comment("Extra blocks beyond the blast's reach that still knock a player down.")
                    .defineInRange("radiusPadding", 2.0D, 0.0D, 64.0D);
            explosionLaunchMultiplier = b
                    .comment("How hard the blast throws the body, multiplied by blast power and",
                            "by how close the player was. At the default, standing next to TNT",
                            "throws a body at about 40 blocks per second.")
                    .defineInRange("launchMultiplier", 10.0D, 0.0D, 1000.0D);
            b.pop();

            b.comment("Being struck by lightning.").push("lightning");
            lightningEnabled = b.define("enabled", true);
            lightningLaunchSpeed = b.comment("Upward launch speed, in blocks per second.")
                    .defineInRange("launchSpeed", 12.0D, 0.0D, 1024.0D);
            b.pop();

            b.comment("Flying an elytra into something.").push("elytraCrash");
            crashEnabled = b.define("enabled", true);
            crashMinDamage = b.comment("Crash damage at or above which the player goes down.")
                    .defineInRange("minDamage", 4.0D, 0.0D, 1024.0D);
            crashLaunchMultiplier = b.comment("How much of the flight speed carries into the body.")
                    .defineInRange("launchMultiplier", 3.0D, 0.0D, 100.0D);
            b.pop();

            b.pop();
            b.comment("States that must never trigger a ragdoll.",
                    "Without these the mod fights the game: every slime block bounce, every",
                    "riptide launch and every elytra takeoff would put the player on the floor.")
                    .push("suppressions");

            suppressionGraceTicks = b
                    .comment("Ticks after a suppressed state ends during which triggers stay off.")
                    .defineInRange("graceTicks", 10, 0, 200);
            suppressRiptide = b.define("riptide", true);
            suppressBounce = b.comment("Slime blocks and beds.").define("bounce", true);
            suppressElytraFlight = b.define("elytraFlight", true);
            suppressCreativeFlight = b.define("creativeFlight", true);
            suppressClimbing = b.comment("Ladders, vines and scaffolding.").define("climbing", true);
            suppressWater = b
                    .comment("Water. A player who clutches a pool after a long fall takes no",
                            "damage in vanilla, but entering water is a large change in speed,",
                            "which is exactly what the impact trigger looks for.")
                    .define("water", true);

            b.pop();
            b.comment("Hurting yourself by being thrown into things.").push("impactDamage");

            impactDamageEnabled = b.define("enabled", true);
            impactDamageThreshold = b
                    .comment("Impact speed, in blocks per second, above which the body takes damage.")
                    .defineInRange("threshold", 12.0D, 0.0D, 1024.0D);
            impactDamageMultiplier = b.defineInRange("multiplier", 0.75D, 0.0D, 100.0D);
            impactDamageMax = b.comment("Most damage one impact can do.")
                    .defineInRange("max", 20.0D, 0.0D, 1024.0D);
            impactDamageCooldownTicks = b
                    .comment("Ticks between impact damage applications, so a skid is not a shredder.")
                    .defineInRange("cooldownTicks", 10, 0, 200);

            b.pop();
            b.comment("What a player leaves behind when they die.").push("corpse");

            corpseEnabled = b
                    .comment("Leave the body behind on death, holding what the player carried.",
                            "With this off, death drops items the vanilla way.")
                    .define("enabled", true);

            corpseLifetimeTicks = b
                    .comment("How long a corpse lasts before it times out, in ticks.",
                            "Anything still inside is dropped rather than destroyed.",
                            "Zero means a body never times out and waits to be looted.")
                    .defineInRange("lifetimeTicks", 20 * 60 * 10, 0, 20 * 60 * 60 * 24);

            corpseDespawnWhenEmpty = b
                    .comment("Remove a corpse as soon as the last item is taken from it.")
                    .define("despawnWhenEmpty", true);

            corpseFreezeTicks = b
                    .comment("Ticks a corpse must lie still before its physics stop entirely.",
                            "A settled body has nothing left to compute, and a world full of",
                            "old corpses should not each be running a solver.")
                    .defineInRange("freezeTicks", 40, 1, 20 * 60);

            b.pop();
            b.comment("Bodies left by mobs, in place of vanilla's toppling death animation.")
                    .push("mobCorpse");

            mobCorpseEnabled = b
                    .comment("Leave a ragdoll behind when a listed mob dies.",
                            "Drops are untouched: a mob's loot still falls on the floor as usual.")
                    .define("enabled", true);

            mobCorpseLifetimeTicks = b
                    .comment("How long a mob's body lasts, in ticks. Zero means forever,",
                            "which is not advised on a busy world.")
                    .defineInRange("lifetimeTicks", 20 * 30, 0, 20 * 60 * 60);

            mobCorpseTypes = b
                    .comment("Which mobs leave a body.",
                            "Only mobs drawn with Minecraft's humanoid model work, because the",
                            "ragdoll is built from the six cuboids that model has. The server",
                            "cannot see which model a mob uses - that is client side - so the",
                            "list is explicit. Adding a non humanoid mob leaves an invisible body.")
                    .defineList("types", java.util.List.of(
                            "minecraft:zombie",
                            "minecraft:husk",
                            "minecraft:drowned",
                            "minecraft:zombie_villager",
                            "minecraft:skeleton",
                            "minecraft:stray",
                            "minecraft:wither_skeleton",
                            "minecraft:piglin",
                            "minecraft:piglin_brute",
                            "minecraft:zombified_piglin",
                            "minecraft:giant"),
                            entry -> entry instanceof String);

            b.pop();
            b.push("sound");
            soundEnabled = b.comment("Play a thud when a player goes down.").define("enabled", true);
            soundVolume = b.defineInRange("volume", 1.0D, 0.0D, 1.0D);
            b.pop();
        }
    }

    public static final class Client {

        public final ForgeConfigSpec.BooleanValue showControlsHint;
        public final ForgeConfigSpec.BooleanValue firstPersonRagdollCamera;
        public final ForgeConfigSpec.BooleanValue hideVanillaDeathAnimation;
        public final ForgeConfigSpec.DoubleValue cameraSmoothing;
        public final ForgeConfigSpec.DoubleValue cameraRoll;

        private Client(ForgeConfigSpec.Builder b) {
            b.comment("Presentation. Client-only.").push("display");

            showControlsHint = b
                    .comment("Show the on-screen controls reminder while ragdolled.")
                    .define("showControlsHint", true);

            firstPersonRagdollCamera = b
                    .comment("In first person, view the world from the ragdoll's own head.",
                            "The camera sits in the head and tumbles with it, rather than",
                            "hovering at standing eye height. Turn off for a fixed view.")
                    .define("firstPersonRagdollCamera", true);

            hideVanillaDeathAnimation = b
                    .comment("Hide Minecraft's own death animation once a body is left behind.",
                            "Vanilla keeps drawing the dead player toppling over, which sits on",
                            "top of the corpse: two bodies in the same place, one of them tipping",
                            "through the other. Turn off if corpses are disabled server side.")
                    .define("hideVanillaDeathAnimation", true);

            cameraSmoothing = b
                    .comment("How much the ragdoll camera is eased, from 0 (locked rigidly to",
                            "the head, twitchy) to 1 (very floaty). The head is a small light",
                            "body at the end of a chain, so some easing is needed to stop every",
                            "twitch of the solve reaching the screen.")
                    .defineInRange("cameraSmoothing", 0.65D, 0.0D, 1.0D);

            cameraRoll = b
                    .comment("How much of the head's roll the camera takes, from 0 to 1.",
                            "Full roll is accurate and quite unpleasant to look at.")
                    .defineInRange("cameraRoll", 0.5D, 0.0D, 1.0D);


            b.pop();
        }
    }
}
