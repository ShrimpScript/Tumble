// The trailer, shot by shot. Each shot fills in the frame state for a local time:
// camera, cast poses, world state, post effects and overlays. Audio cues are declared
// next to the shots that cause them so picture and sound cannot drift apart.
import * as THREE from 'three';
import { ease, clamp01, lerp, v3, range, path, damped, look, shake, noise1 } from './camera.js';
import { sharedUniforms as U } from './terrain.js';

const TICK = 1 / 20;

export function buildTimeline(ctx) {
  const { engine, cast, sims, particles, overlay, fp, so, heights, fallMeta, montageMeta, solver } = ctx;
  const cam = engine.camera;
  const S = (x, y, z) => v3(x - so[0], y - so[1], z - so[2]);
  const shots = [];
  const cues = [];
  const cue = (t, name, vol = 1, pitch = 1, extra = {}) => cues.push({ t, name, vol, pitch, ...extra });
  const shot = (name, start, end, fn, setup) => shots.push({ name, start, end, fn, setup });

  // -- world helpers ------------------------------------------------------------------------

  // Highest support under an entity of half-width 0.3 whose feet are near yRef: the
  // first solid block scanning down from one step above its feet, like collision would.
  // Highest support under an entity of half-width 0.3 whose feet are near yRef: the
  // first solid block scanning down from one step above its feet, like collision would.
  // A support needs two free cells above it (feet and head); otherwise it is a wall.
  function standY(x, z, yRef = 140, depth = 12) {
    let best = -999;
    const p = new THREE.Vector3();
    const solidAt = (bx, y, bz) => solver.at(p.set(bx + 0.5 - so[0], y + 0.5 - so[1], bz + 0.5 - so[2])) === 1;
    for (const bx of [Math.floor(x - 0.299), Math.floor(x + 0.299)]) {
      for (const bz of [Math.floor(z - 0.299), Math.floor(z + 0.299)]) {
        for (let y = Math.floor(yRef + 0.6); y > yRef - depth; y--) {
          if (solidAt(bx, y, bz)) {
            best = Math.max(best, solidAt(bx, y + 1, bz) || solidAt(bx, y + 2, bz) ? 999 : y + 1);
            break;
          }
        }
      }
    }
    return best;
  }

  /**
   * A player walking a path on the real terrain at vanilla walking speed (4.317 b/s),
   * jumping up one-block steps (0.42 b/tick jump, 0.08 gravity, 0.98 drag) and dropping
   * down them, with the walk animation integrated exactly like WalkAnimationState.
   */
  function walker(points, tStart, opts = {}) {
    const speed = (opts.speed ?? 4.317) / 20;
    const pts = points.map(([x, z]) => new THREE.Vector2(x, z));
    const cum = [0];
    for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + pts[i].distanceTo(pts[i - 1]));
    const total = cum[cum.length - 1];
    const at = (s) => {
      s = Math.min(Math.max(s, 0), total);
      let i = 1;
      while (i < cum.length - 1 && cum[i] < s) i++;
      const u = (s - cum[i - 1]) / Math.max(cum[i] - cum[i - 1], 1e-9);
      return pts[i - 1].clone().lerp(pts[i], u);
    };
    const N = Math.ceil((opts.duration ?? 20) * 20) + 2;
    const ticks = [];
    let s = 0, y = standY(pts[0].x, pts[0].y, opts.y0 ?? 140, 120), vy = 0, ground = true, ls = 0, la = 0, walkDist = 0;
    const dir0 = at(0.5).sub(at(0));
    let yaw = opts.yaw0 ?? (Math.atan2(-dir0.x, dir0.y) * 180 / Math.PI);
    let nextStep = 1;
    for (let k = 0; k < N; k++) {
      const p = at(s);
      ticks.push({ x: p.x, y, z: p.y, yaw, ls, la, walkDist, s });
      const tAbs = tStart + k * TICK;
      let ds = s < total ? Math.min(speed, total - s) : 0;
      // Look ahead for a step up and jump before reaching it.
      const ahead = at(s + ds + 0.35);
      const gAhead = standY(ahead.x, ahead.y, y + 1.2);
      if (ground && ds > 0 && gAhead > y + 0.6 && gAhead <= y + 1.01) { vy = 0.42; ground = false; }
      const nxt = at(s + ds);
      if (standY(nxt.x, nxt.y, y + Math.max(vy, 0) + 0.01) > y + 0.6) ds = 0;
      s += ds;
      const g = standY(at(s).x, at(s).y, y + 0.01);
      if (!ground) { y += vy; vy = (vy - 0.08) * 0.98; }
      if (g > y + 1.3) { /* wall: stay */ } else if (y <= g) { y = g; vy = 0; ground = true; } else if (ground && y > g + 1e-3) { ground = false; vy = 0; }
      const target = Math.min(ds * 4, 1);
      la += (target - la) * 0.4;
      ls += la;
      walkDist += ds * 0.6;
      if (walkDist > nextStep && ground && opts.steps !== false && tAbs >= (opts.stepsFrom ?? -1)) {
        cue(tAbs, opts.stepSound ?? 'step/grass', opts.stepVol ?? 0.35, 1.0, { random: k });
        nextStep = Math.floor(walkDist) + 1;
      }
      if (ds > 0) {
        const d = at(s + 1.6).sub(at(Math.max(s - 0.4, 0)));
        if (d.lengthSq() > 1e-6) {
          const want = Math.atan2(-d.x, d.y) * 180 / Math.PI;
          let dy = ((want - yaw + 540) % 360) - 180;
          yaw += Math.max(-9, Math.min(9, dy * 0.22));
        }
      } else if (opts.yawEnd !== undefined) {
        let dy = ((opts.yawEnd - yaw + 540) % 360) - 180;
        yaw += Math.max(-10, Math.min(10, dy * 0.3));
      }
    }
    return (t) => {
      const f = Math.min(Math.max((t - tStart) * 20, 0), N - 2);
      const i = Math.floor(f), u = f - i;
      const a = ticks[i], b = ticks[i + 1];
      let dyaw = ((b.yaw - a.yaw + 540) % 360) - 180;
      return { x: lerp(a.x, b.x, u), y: lerp(a.y, b.y, u), z: lerp(a.z, b.z, u), yaw: a.yaw + dyaw * u,
        ls: lerp(a.ls, b.ls, u), la: lerp(a.la, b.la, u), walkDist: lerp(a.walkDist, b.walkDist, u), moving: b.s > a.s };
    };
  }

  // Pose a humanoid from a walker sample, with optional head look.
  function poseWalker(h, w, extra = {}) {
    const p = S(w.x, w.y, w.z);
    h.group.visible = true;
    h.pose(Object.assign({ x: p.x, y: p.y, z: p.z, bodyYaw: w.yaw, limbSwing: w.ls, limbAmount: w.la, age: w.ls * 2 + 20,
      heldPose: h.held ? 'item' : undefined }, extra));
    const l = engine.lightNear(v3(p.x, p.y + 1, p.z));
    h.setLight(l[0], l[1]);
  }

  function showRagdoll(h, sim, tick) {
    h.group.visible = true;
    const b = sim.sample(tick);
    h.setRagdoll(b);
    const c = sim.com(tick);
    const l = engine.lightNear(c);
    h.setLight(l[0], l[1]);
    return c;
  }

  // Captions fade like /title (10 tick fade in, 20 out).
  const fade = (lt, a, b, fin = 0.5, fout = 0.6) => clamp01((lt - a) / fin) * clamp01((b - lt) / fout);

  // Default world look: a clear late morning, sun in the east (vanilla's sun only
  // ever moves in the x-y plane).
  function world(F, o = {}) {
    engine.setSun(o.sun ?? [0.62, 0.78, 0.0], { color: o.sunColor ?? 0xfff1dc });
    U.shadowOn.value = o.shadows === false ? 0 : 1;
    U.shadowStrength.value = o.shadowStrength ?? 0.42;
    U.gammaSetting.value = o.gamma ?? 0.5;
    U.fogStart.value = o.fog?.[0] ?? 150;
    U.fogEnd.value = o.fog?.[1] ?? 188;
    U.fogColor.value.setRGB(...(o.fogColor ?? [0.73, 0.83, 1.0]));
    U.caveFog.value = o.caveFog ?? 0;
    U.exposure.value = o.exposure ?? 1;
    U.minLight.value = o.minLight ?? 0;
    U.flash.value = o.flash ?? 0;
    F.render = Object.assign({ shadowFocus: o.focus ?? cam.position, skyTop: 0x78a7ff, clouds: true, cloudOffset: (o.time ?? 0) * 0.6 }, o.render);
    engine.setBlasted(!!o.blasted);
    engine.shadowSize = o.shadowSize ?? 48;
  }

  // Nudges a camera up out of terrain (for moving cameras near the ground).
  const lift = (p, r = 0.35) => {
    for (let k = 0; k < 24 && !solver.clear(p, r); k++) p.y += 0.2;
    return p;
  };
  const coms = (sim, a, b, n = 5) => Array.from({ length: n }, (_, i) => sim.com(lerp(a, b, i / (n - 1))));

  const solverFind = (targets, o) => solver.find(targets, Object.assign({ fluidWeight: 12 }, o));

  const P = fallMeta.player, C = fallMeta.creeper;
  const steveRim = S(P[0], P[1], P[2]);
  const creeperRim = S(C[0], C[1], C[2]);
  const fall = sims.fall;
  const T_BOOM = 26.5;
  const creeperYaw = Math.atan2(-(P[0] - C[0]), P[2] - C[2]) * 180 / Math.PI;

  cast.steve.hold(cast.items.pickaxe);

  // ==========================================================================================
  // 1. COLD OPEN  0.0 - 3.5   Mid-fall in slow motion. Freeze. "Yep. That's me."
  // ==========================================================================================
  const OPEN_FREEZE = 2.6;
  const openTick = (lt) => 30 + Math.min(lt, OPEN_FREEZE) * 20 * 0.25;
  const fallRef = () => sims.fall;
  const openCam = solverFind(coms(fallRef(), 30, 44, 4), { dist: [4, 9], elev: -55, elevWeight: 0.12, prefDist: 6, clearance: 0.6 });
  shot('cold-open', 0.0, 3.5, (F, lt) => {
    const tick = openTick(lt);
    world(F, { blasted: true, focus: fallRef().com(tick), time: lt, caveFog: 0.15 });
    const c = showRagdoll(cast.steve, fallRef(), tick);
    const drift = v3(Math.sin(lt * 0.4) * 0.3, -Math.min(lt, OPEN_FREEZE) * 0.35, 0);
    look(cam, openCam.clone().add(drift), c.clone().add(v3(0, 0.2, 0)), 46, -0.1);
    const frozen = lt >= OPEN_FREEZE;
    F.ticks = 1000 + Math.min(lt, OPEN_FREEZE) * 5;
    F.post = { aperture: 0.04, focus: openCam.distanceTo(c), desat: frozen ? 0.25 : 0, contrast: frozen ? 1.12 : 1.06,
      fade: lt < 0.25 ? 1 - lt / 0.25 : 0 };
    F.draw = (g, ov) => {
      if (frozen) ov.chat(g, ['<Steve> Yep. That\'s me.'], clamp01((lt - OPEN_FREEZE) / 0.15));
    };
  });
  cue(0.0, 'sfx/wind', 0.5);
  cue(OPEN_FREEZE, 'sfx/scratch', 0.9);

  // ==========================================================================================
  // 2. REWIND  3.5 - 5.0   Back up the shaft, the crater fills in, the creeper un-swells.
  // ==========================================================================================
  shot('rewind', 3.5, 5.0, (F, lt) => {
    const u = lt / 1.5;
    const tick = lerp(openTick(OPEN_FREEZE), 0, ease.inOut(u));
    world(F, { blasted: true, time: 10 - lt * 8, caveFog: 0.15 });
    const c = showRagdoll(cast.steve, fallRef(), tick);
    look(cam, openCam.clone().add(v3(0, -OPEN_FREEZE * 0.35, 0)), c.clone().add(v3(0, 0.2, 0)), lerp(46, 60, u), -0.1);
    F.ticks = 2000 - lt * 120;
    F.post = { rewind: 1, time: lt, aberration: 0.6, desat: 0.2, flash: u > 0.85 ? (u - 0.85) / 0.15 : 0 };
    F.draw = (g, ov) => ov.chat(g, ['<Steve> Yep. That\'s me.'], 1 - u);
  });
  cue(3.5, 'sfx/rewind', 0.8);

  // ==========================================================================================
  // ACT ONE  5.0 - 21.0   A perfectly normal morning.
  // ==========================================================================================
  // One continuous walk from the cherry grove to the rim, shared by shots 4, 5 and 6.
  // Route planned with world/plan_path.py (A* over walkable columns), corners rounded.
  const ROUTE = [[1471, 162], [1472, 161], [1472, 160], [1473, 160], [1474, 159], [1475, 158], [1476, 157], [1477, 156],
    [1478, 155], [1479, 154], [1480, 153], [1481, 152], [1482, 151], [1483, 150], [1484, 149], [1485, 148], [1486, 147],
    [1487, 146], [1488, 145], [1489, 144], [1490, 143], [1491, 142], [1492, 141], [1492, 140], [1493, 139], [1494, 139],
    [1495, 138], [1496, 137.5]].map(([x, z]) => [x + 0.5, z + 0.5]);
  ROUTE.push([P[0], P[2]]);
  const chaikin = (pts) => {
    const out = [pts[0]];
    for (let i = 0; i < pts.length - 1; i++) {
      const [a, b] = [pts[i], pts[i + 1]];
      out.push([a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25], [a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75]);
    }
    out.push(pts[pts.length - 1]);
    return out;
  };
  const route = chaikin(chaikin(ROUTE));
  let routeLen = 0;
  for (let i = 1; i < route.length; i++) routeLen += Math.hypot(route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1]);
  const ARRIVE = 16.6;
  const walk = walker(route, ARRIVE - routeLen / 4.317 - 0.4, { duration: 16, yawEnd: -90, stepsFrom: 9.0 });
  shot('establishing', 5.0, 9.0, (F, lt) => {
    const u = lt / 4;
    // Morning haze: also keeps the edge of the generated area out of sight.
    world(F, { time: 5 + lt, fog: [55, 128], fogColor: [0.78, 0.86, 1.0], shadowSize: 120 });
    const pth = path([[-118, 52, 78], [-96, 40, 58], [-74, 31, 42]]);
    const pos = pth(ease.inOut(u));
    look(cam, pos, v3(-6 + u * 4, -14, 0), 64);
    F.render.shadowFocus = v3(-40, -12, 30);
    const w = walk(9.0);
    poseWalker(cast.steve, w);
    particles.clear();
    F.post = { flash: lt < 0.35 ? 1 - lt / 0.35 : 0 };
    F.draw = (g, ov) => ov.caption(g, 'A peaceful survival world.', fade(lt, 0.5, 3.7));
  });

  shot('the-walk', 9.0, 13.0, (F, lt, t) => {
    world(F, { time: t, shadowSize: 40 });
    const w = walk(t);
    poseWalker(cast.steve, w, { headPitch: 4, headYaw: Math.sin(lt * 0.9) * 12 });
    const p = S(w.x, w.y, w.z);
    // Leading shot: the camera backs up ahead of him, low, the grove behind him.
    const fwd = v3(-Math.sin(w.yaw * Math.PI / 180), 0, Math.cos(w.yaw * Math.PI / 180));
    const side = v3(fwd.z, 0, -fwd.x);
    const target = p.clone().add(v3(0, 1.1, 0));
    const camPos = damped((tt) => {
      const ww = walk(tt);
      const pp = S(ww.x, ww.y, ww.z);
      return pp.add(fwd.clone().multiplyScalar(6.2)).add(side.clone().multiplyScalar(1.8)).add(v3(0, 1.25, 0));
    }, t - 1.5, t, 3.5);
    lift(camPos);
    look(cam, camPos, target, 40);
    F.render.shadowFocus = p;
    F.particleTime = t;
    F.post = { aperture: 0.035, focus: camPos.distanceTo(target) };
  }, () => particles.petals(8, 14, S(1480, 95, 152), 12, 9, 110, 11));

  shot('pov', 13.0, 15.0, (F, lt, t) => {
    world(F, { time: t, shadowSize: 40 });
    const w = walk(t);
    const p = S(w.x, w.y, w.z);
    const yawR = w.yaw * Math.PI / 180;
    // GameRenderer.bobView, with the player's own bob amount while walking.
    const bob = 0.1 * clamp01(w.la * 1.2);
    const f1 = -w.walkDist;
    const eye = p.clone().add(v3(0, 1.62, 0));
    cam.position.copy(eye);
    cam.rotation.order = 'YXZ';
    const pitch = lerp(14, 26, ease.inOut(range(lt, 0.6, 2.0)));
    const toHole = Math.atan2(-(1502 - w.x), 139 - w.z);
    const aimYaw = lerp(yawR, toHole, 0.55);
    cam.rotation.set(-pitch * Math.PI / 180, Math.PI - aimYaw, 0);
    cam.updateMatrixWorld();
    cam.translateX(Math.sin(f1 * Math.PI) * bob * 0.5);
    cam.translateY(-Math.abs(Math.cos(f1 * Math.PI) * bob));
    cam.rotateZ(Math.sin(f1 * Math.PI) * bob * 3 * Math.PI / 180);
    cam.rotateX(-Math.abs(Math.cos(f1 * Math.PI - 0.2) * bob) * 5 * Math.PI / 180);
    cam.fov = 70; cam.updateProjectionMatrix(); cam.updateMatrixWorld();
    cast.steve.group.visible = false;
    // First person pickaxe: ItemInHandRenderer.applyItemArmTransform plus the same bob.
    F.fp = true;
    fp.root.position.set(0.56 + Math.sin(f1 * Math.PI) * bob * 0.5, -0.52 - Math.abs(Math.cos(f1 * Math.PI) * bob), -0.72);
    fp.root.rotation.set(0, 0, 0);
    fp.attack.rotation.set(0, 0, 0);
    const l = engine.lightNear(eye);
    cast.items.pickaxeFP.mat.uniforms.lightLevel.value.set(l[0], l[1]);
    F.render.shadowFocus = p;
    F.particleTime = t;
    F.draw = (g, ov) => ov.hud(g, { hotbar: HOTBAR, selected: 0, health: 20, food: 18, xp: 0.42, level: 3, crosshair: true });
  }, () => particles.petals(8, 16, S(1488, 95, 146), 10, 8, 60, 12));

  const viewCam = solver.find([steveRim.clone().add(v3(0, 1, 0)), steveRim.clone().add(v3(3, -4, 0))],
    { dist: [9, 13], elev: 24, az: 10, azWeight: 0.06, prefDist: 11, clearance: 0.6 });
  shot('the-view', 15.0, 19.0, (F, lt, t) => {
    world(F, { time: t, focus: steveRim });
    const w = walk(t);
    const look01 = ease.inOut(range(lt, 1.8, 2.6));
    poseWalker(cast.steve, w, { headPitch: lerp(5, 38, look01) - ease.inOut(range(lt, 3.0, 3.8)) * 22 });
    const p = viewCam.clone().lerp(steveRim.clone().add(v3(0, 1.4, 0)), 0.18 * ease.inOut(lt / 4));
    look(cam, p, steveRim.clone().add(v3(0.6, 0.8, 0)), 40);
    F.post = { aperture: 0.02, focus: p.distanceTo(steveRim) };
    F.draw = (g, ov) => ov.caption(g, 'Nothing could possibly go wrong.', fade(lt, 1.2, 3.8));
  });

  shot('vertigo', 19.0, 21.0, (F, lt, t) => {
    world(F, { time: t, focus: v3(3, -40, 0), caveFog: 0.4 });
    cast.steve.group.visible = false;
    const u = ease.inOut(lt / 2);
    const eye = steveRim.clone().add(v3(0.15, 1.62, 0));
    // Dolly zoom: lean forward and over while the lens widens.
    cam.position.copy(eye).add(v3(u * 1.3, -u * 0.6, 0.2 * u));
    cam.rotation.order = 'YXZ';
    cam.rotation.set(-lerp(58, 74, u) * Math.PI / 180, Math.PI * 1.5 - 0.2, 0);
    cam.fov = lerp(38, 84, u);
    cam.updateProjectionMatrix();
    cam.updateMatrixWorld();
    F.render.shadowFocus = v3(2, -20, 0);
    F.post = { vignette: 0.45 + u * 0.2 };
  });

  // ==========================================================================================
  // ACT TWO  21.0 - 35.3   The incident.
  // ==========================================================================================
  const creeperWalk = walker([[1484.6, 138.1], [1490.0, 138.3], [C[0], C[2]]], 21.0,
    { speed: 2.8, duration: 8, yawEnd: creeperYaw, stepSound: 'step/grass', stepVol: 0.12 });
  const steveIdle = (h, t, extra = {}) => {
    h.group.visible = true;
    h.pose(Object.assign({ x: steveRim.x, y: steveRim.y, z: steveRim.z, bodyYaw: -90, headPitch: 30, heldPose: 'item', age: t * 20 }, extra));
    const l = engine.lightNear(steveRim.clone().add(v3(0, 1, 0)));
    h.setLight(l[0], l[1]);
  };
  const poseCreeper = (t, swell = 0) => {
    const w = creeperWalk(t);
    const p = S(w.x, w.y, w.z);
    cast.creeper.group.visible = true;
    cast.creeper.pose({ x: p.x, y: p.y, z: p.z, bodyYaw: w.yaw, limbSwing: w.ls, limbAmount: w.la, swell,
      headYaw: 0, headPitch: 5 });
    const l = engine.lightNear(p.clone().add(v3(0, 1, 0)));
    cast.creeper.setLight(l[0], l[1]);
    return p;
  };

  const ironyCam = steveRim.clone().add(v3(3.6, 1.35, 0.55));
  shot('dramatic-irony', 21.0, 25.0, (F, lt, t) => {
    world(F, { time: t, focus: steveRim });
    steveIdle(cast.steve, t, { headPitch: 12 + Math.sin(lt * 0.7) * 3, headYaw: Math.sin(lt * 0.5) * 8 });
    const cp = poseCreeper(t);
    const camPos = ironyCam.clone().add(v3(-lt * 0.12, 0, 0));
    const tgt = steveRim.clone().add(v3(-2.2, 1.25, 0.35));
    look(cam, camPos, tgt, 40);
    // Rack focus from Steve to the creeper as it arrives.
    const rf = ease.inOut(range(lt, 2.6, 3.6));
    F.post = { aperture: 0.09, focus: lerp(camPos.distanceTo(steveRim) + 0.3, camPos.distanceTo(cp) + 0.2, rf) };
  });

  shot('hiss', 25.0, 25.9, (F, lt, t) => {
    world(F, { time: t, focus: creeperRim });
    steveIdle(cast.steve, t, { headPitch: 14 });
    const swell = clamp01((t - 25.0) / 1.5);
    poseCreeper(t, swell);
    const face = creeperRim.clone().add(v3(0, 1.28, 0));
    const fwd = v3(-Math.sin(creeperYaw * Math.PI / 180), 0, Math.cos(creeperYaw * Math.PI / 180));
    const camPos = face.clone().addScaledVector(fwd, 1.9 - lt * 0.5).add(v3(0, -0.05, 0));
    look(cam, camPos, face, 34);
    shake(cam, t, 0.15 + swell * 0.25, 0.05);
    F.post = { aperture: 0.05, focus: camPos.distanceTo(face), vignette: 0.4 };
  });
  cue(25.0, 'random/fuse', 1.0);

  // From the south: Steve's face turns toward us, the swelling creeper beside him.
  const turnCam = lift(S(1497.25, 92.35, 140.45), 0.3);
  shot('the-turn', 25.9, 26.5, (F, lt, t) => {
    world(F, { time: t, focus: steveRim });
    const turn = ease.inOut(range(lt, 0.05, 0.42));
    steveIdle(cast.steve, t, { headPitch: lerp(14, 4, turn), headYaw: lerp(0, 62, turn) });
    poseCreeper(t, clamp01((t - 25.0) / 1.5));
    const head = steveRim.clone().add(v3(0, 1.55, 0));
    const frame = head.clone().lerp(creeperRim.clone().add(v3(0, 1.3, 0)), 0.47);
    look(cam, turnCam.clone().lerp(frame, 0.08 * lt), frame, 47);
    F.post = { aperture: 0.05, focus: turnCam.distanceTo(head) };
  });

  // The blast and the long way down. Scene time runs through a speed ramp.
  const boom = (F, lt, t, o) => {
    world(F, Object.assign({ time: t, blasted: true }, o));
    F.particleTime = o.particleTime ?? t;
  };
  const boomCam = solver.find([creeperRim.clone().add(v3(0, 1, 0)), steveRim.clone().add(v3(0, 1, 0)), fall.com(5)],
    { dist: [8, 13], elev: 28, az: 95, azWeight: 0.06, prefDist: 10, clearance: 0.8 });
  shot('boom', T_BOOM, 27.5, (F, lt, t) => {
    const tick = lt * 20 * 0.25;            // 0 -> 5 at quarter speed
    const sceneT = T_BOOM + tick * TICK;
    boom(F, lt, t, { focus: v3(0, -9, -1), particleTime: sceneT });
    U.flash.value = Math.max(0, 1 - lt / 0.25) * 3;
    U.flashPos.value.copy(creeperRim).add(v3(0, 0.8, 0));
    U.flashRadius.value = 14;
    showRagdoll(cast.steve, fall, tick);
    look(cam, boomCam, v3(-0.3, -9.6, -1.6), 50);
    shake(cam, t, Math.max(0, 1 - lt * 1.1), 0.5);
    F.post = { flash: lt < 0.1 ? 1 : Math.max(0, 1 - (lt - 0.1) / 0.25) * 0.8, aberration: Math.max(0, 1.5 - lt * 2) };
  }, () => particles.explosion(T_BOOM, creeperRim.clone().add(v3(0, 0.3, 0)), 21));
  cue(T_BOOM, 'random/explode', 1.0, 1.0, { random: 2 });

  const splatCam = solver.find(coms(fall, 5, 14, 4), { dist: [2.5, 4.5], elev: 5, az: 120, azWeight: 0.03, prefDist: 3.2 });
  shot('splat', 27.5, 28.2, (F, lt, t) => {
    const tick = 5 + lt * 20 * 0.65;
    boom(F, lt, t, { focus: v3(1.6, -10, -3.6), particleTime: T_BOOM + 0.25 + lt * 0.65 });
    const c = showRagdoll(cast.steve, fall, tick);
    look(cam, splatCam, c.clone().lerp(fall.com(9), 0.5), 40);
    shake(cam, t, Math.max(0, 0.5 - lt), 0.2);
  }, () => particles.explosion(T_BOOM, creeperRim.clone().add(v3(0, 0.3, 0)), 21));
  cue(27.5, 'damage/hit', 1.0, 1.0, { random: 1 });

  const shaftCam = solverFind(coms(fall, 14, 50, 6), { dist: [5, 22], elev: 70, elevWeight: 0.1, prefDist: 12, distWeight: 0.2, allowSolid: 0 });
  shot('shaft', 28.2, 30.0, (F, lt, t) => {
    const tick = 14 + lt * 20;
    boom(F, lt, t, { focus: fall.com(tick), caveFog: 0.2 });
    const c = showRagdoll(cast.steve, fall, tick);
    look(cam, shaftCam.clone().add(v3(0, -lt * 1.2, 0)), fall.com(tick + 2).add(v3(0, -1.0, 0)), 58);
  });
  cue(28.2, 'sfx/whoosh', 0.7);

  const ledgeCam = solverFind(coms(fall, 48, 84, 6), { dist: [3.5, 7], elev: 25, prefDist: 4.5 });
  shot('ledge', 30.0, 31.7, (F, lt, t) => {
    const tick = 50 + lt * 20;
    boom(F, lt, t, { focus: v3(3, -48, 1), caveFog: 0.35 });
    showRagdoll(cast.steve, fall, tick);
    look(cam, ledgeCam, fall.com(tick + 2), 50);
    shake(cam, t, Math.max(0, 0.55 - lt * 1.4), 0.25);
  });
  cue(30.0, 'damage/hit', 1.0, 0.95, { random: 2 });

  const slipCam = solverFind(coms(fall, 84, 108, 6), { dist: [4, 9], elev: 10, prefDist: 6 });
  shot('slip', 31.7, 32.8, (F, lt, t) => {
    const tick = 84 + lt * 20;
    boom(F, lt, t, { focus: v3(3, -58, 0), caveFog: 0.35 });
    showRagdoll(cast.steve, fall, tick);
    look(cam, slipCam, fall.com(tick + 2), 54);
  });
  cue(32.8, 'damage/hit', 1.0, 1.05, { random: 3 });

  const rocksCam = solverFind(coms(fall, 106, 120, 3), { dist: [2.5, 4.5], elev: 20, prefDist: 3 });
  shot('rocks', 32.8, 33.5, (F, lt, t) => {
    const tick = 106 + lt * 20;
    boom(F, lt, t, { focus: v3(3.2, -65.5, -0.8), caveFog: 0.4 });
    const c = showRagdoll(cast.steve, fall, tick);
    look(cam, rocksCam, c, 40);
    shake(cam, t, Math.max(0, 0.45 - lt * 1.5), 0.2);
  });

  const slideCam = solverFind(coms(fall, 140, 162, 4), { dist: [3, 5.5], elev: -15, prefDist: 3.8 });
  shot('slide', 33.5, 34.5, (F, lt, t) => {
    const tick = 140 + lt * 20;
    boom(F, lt, t, { focus: v3(4.4, -67, -1.2), caveFog: 0.4 });
    const c = showRagdoll(cast.steve, fall, tick);
    look(cam, slideCam, c, 46);
  });

  const dropCam = solverFind([...coms(fall, 160, 176, 4), fall.com(176).add(v3(0, 0.3, 0))], { dist: [3, 8], elev: -40, prefDist: 4.5, clearance: 0.4 });
  shot('final-drop', 34.5, 35.3, (F, lt, t) => {
    const tick = 160 + lt * 20;
    boom(F, lt, t, { focus: v3(5, -78, -1.6), caveFog: 0.3 });
    const c = showRagdoll(cast.steve, fall, tick);
    look(cam, dropCam, c.clone().lerp(fall.com(176), 0.3), 72);
  });
  cue(35.3, 'damage/hit', 1.0, 0.9, { random: 4 });
  cue(35.3, 'sfx/thud', 1.0);

  shot('black', 35.3, 36.0, (F) => {
    world(F, { blasted: true });
    look(cam, v3(0, -200, 0), v3(0, -201, 0), 70);
    F.post = { fade: 1 };
  });

  // ==========================================================================================
  // 15. YOU DIED!  36.0 - 40.0
  // ==========================================================================================
  const deathTick = fallMeta.deathTick;
  const diedCam = solver.find([fall.com(deathTick + 4)], { dist: [2.8, 4.5], elev: 55, prefDist: 3.5 });
  shot('you-died', 36.0, 40.0, (F, lt, t) => {
    world(F, { blasted: true, focus: v3(5, -80, -1.5), caveFog: 0.2 });
    const c = showRagdoll(cast.steve, fall, deathTick + 4);
    look(cam, diedCam.clone().lerp(c, 0.04 * lt), c, 50);
    F.post = { desat: 0.2, fade: lt < 0.4 ? 1 - lt / 0.4 : 0 };
    const enabled = lt > 1.0;   // the buttons stay disabled for the first 20 ticks
    const cursorFrom = [overlay.gw / 2 + 70, overlay.gh / 2 + 70];
    const target = [overlay.gw / 2 + 12, Math.floor(overlay.gh / 4) + 80];
    const cu = ease.inOut(range(lt, 1.6, 3.0));
    const cursor = [lerp(cursorFrom[0], target[0], cu), lerp(cursorFrom[1], target[1], cu)];
    F.draw = (g, ov) => {
      ov.death(g, { message: 'Steve experienced kinetic energy whilst trying to escape Creeper', enabled,
        hover: cu > 0.98 ? 0 : -1, cursor: lt > 1.2 ? cursor : null });
    };
  });
  cue(39.6, 'random/click', 0.6);

  // ==========================================================================================
  // 16. TITLE  40.0 - 44.0   The logo falls into place over the scene of the crime.
  // ==========================================================================================
  const letterDrop = (t, t0, i, seed) => {
    // Each letter drops from above, lands with a damped bounce and a little spin.
    const lt = t - (t0 + i * 0.12);
    if (lt < 0) return { dx: 0, dy: -900, rot: 0, a: 0 };
    const fallT = 0.32;
    if (lt < fallT) {
      const u = lt / fallT;
      return { dx: 0, dy: -700 * (1 - u * u), rot: (1 - u) * (seed % 2 ? 40 : -35), a: 1 };
    }
    const b = lt - fallT;
    return { dx: 0, dy: -60 * Math.exp(-b * 7) * Math.abs(Math.sin(b * 16)), rot: Math.exp(-b * 6) * Math.sin(b * 14) * 9 * (seed % 2 ? 1 : -1), a: 1 };
  };
  shot('title', 40.0, 44.0, (F, lt, t) => {
    world(F, { blasted: true, focus: v3(5, -80, -1.5), caveFog: 0.4 });
    showRagdoll(cast.steve, fall, deathTick + 4);
    look(cam, diedCam.clone().lerp(fall.com(deathTick + 4), 0.15), fall.com(deathTick + 4), 50);
    F.post = { aperture: 0.35, focus: 0.5, maxBlur: 14, bright: 0.45, desat: 0.3, fade: lt < 0.15 ? 1 - lt / 0.15 : 0 };
    F.draw = (g, ov) => {
      const letters = [0, 1, 2, 3, 4, 5].map((i) => letterDrop(t, 40.25, i, i));
      ov.logo(g, ov.w / 2, ov.h * 0.44, ov.h * 0.95, letters);
      ov.plain(g, 'Ragdoll physics for Minecraft', ov.w / 2, ov.h * 0.8, ov.h * 0.045, '#e8eaef', 'PlexR', 'center', clamp01((lt - 1.6) / 0.5));
    };
  });
  for (let i = 0; i < 6; i++) cue(40.25 + i * 0.12 + 0.32, 'sfx/letter', 0.5, 0.9 + i * 0.05);

  // ==========================================================================================
  // 17. MONTAGE  44.0 - 59.0
  // ==========================================================================================
  const M = montageMeta;
  const capt = (lt, text, a = 0.3, b = 2.8) => (g, ov) => ov.caption(g, text, fade(lt, a, b, 0.25, 0.3));

  // Lightning strikes ------------------------------------------------------------------------
  const LP = S(...M.lightning);
  const T_STRIKE = 45.25;
  const bolt = cast.makeBolt(LP.clone(), 11);
  engine.scene.add(bolt);
  bolt.visible = false;
  const lightningCam = LP.clone().add(v3(-2.9, 0.95, -4.9));
  shot('lightning', 44.0, 47.0, (F, lt, t) => {
    const flashK = t >= T_STRIKE ? Math.max(0, 1 - (t - T_STRIKE) / 0.35) * (0.6 + 0.4 * Math.abs(Math.sin((t - T_STRIKE) * 60))) : 0;
    world(F, { time: t, focus: LP, sun: [0.3, 0.9, 0.1], fogColor: [0.32, 0.34, 0.38], fog: [40, 110],
      shadows: false, render: { skyTop: 0x4a5160, cloudDim: 0.45, skyDim: 0.55 + flashK * 0.6 } });
    U.exposure.value = 1 + flashK * 0.6;
    // Thunder darkens the sky (Level.getSkyDarken); the flash lights everything up.
    U.daylight.value = lerp(0.32, 1.0, flashK);
    cast.rain.group.visible = true;
    cast.rain.mat.uniforms.time.value = t;
    cast.rain.mat.uniforms.bright.value = 0.45 + flashK * 0.5;
    if (!cast.rain.builtFor || cast.rain.builtFor !== 'lightning') { cast.rain.build(lightningCam, (x, z) => standY(x + so[0], z + so[2], LP.y + so[1] + 4, 30) - so[1]); cast.rain.builtFor = 'lightning'; }
    bolt.visible = t >= T_STRIKE && t < T_STRIKE + 0.4 && Math.floor((t - T_STRIKE) * 25) % 3 !== 2;
    if (t < T_STRIKE) {
      cast.steve.group.visible = true;
      cast.steve.hold(cast.items.sword);
      cast.steve.pose({ x: LP.x, y: LP.y, z: LP.z, bodyYaw: 150, headPitch: lerp(0, -38, ease.inOut(range(lt, 0.2, 0.9))), headYaw: 10, heldPose: 'item', age: t * 20 });
      cast.steve.setLight(15, 0);
    } else {
      showRagdoll(cast.steve, sims.lightning, (t - T_STRIKE) * 20);
    }
    const up = t >= T_STRIKE ? Math.max(0, sims.lightning.com((t - T_STRIKE) * 20).y - LP.y - 1) : 0;
    look(cam, lightningCam.clone().add(v3(-lt * 0.3, 0.2, -lt * 0.45)), LP.clone().add(v3(0, 1.6 + up * 0.6, 0)), 58);
    shake(cam, t, t >= T_STRIKE ? Math.max(0, 0.9 - (t - T_STRIKE) * 1.5) : 0, 0.18);
    F.post = { flash: flashK * 0.55, flashColor: 0xdde6ff, saturation: 0.85, contrast: 1.1 };
    F.draw = capt(lt, 'Lightning strikes', 0.3, 2.85);
  });
  cue(T_STRIKE, 'ambient/weather/thunder', 1.0, 1.0, { random: 1 });
  cue(T_STRIKE + 0.05, 'damage/hit', 0.8, 1.0, { random: 1 });

  // Elytra crash ----------------------------------------------------------------------------
  const EP = S(...M.elytra);
  const T_CRASH = 48.35;
  // A descending glide that clears the hillside (ground 92-96 to the east) and slips
  // under the canopy into the trunk.
  const glideFrom = S(1488, 97.0, 141.5);
  const glidePitch = Math.atan2(glideFrom.y - EP.y, glideFrom.x - EP.x) * 180 / Math.PI;
  const glide = (t) => glideFrom.clone().lerp(EP, ease.linear(clamp01((t - 47.0) / (T_CRASH - 47.0))));
  shot('elytra', 47.0, T_CRASH, (F, lt, t) => {
    world(F, { time: t, focus: glide(t), shadowSize: 40 });
    const p = glide(t);
    cast.steve.group.visible = true;
    cast.steve.hold(null);
    cast.elytra.holder.visible = true;
    cast.elytra.spread(1);
    cast.steve.pose({ x: p.x, y: p.y, z: p.z, bodyYaw: 90, rootPitch: -90 - glidePitch, flying: true, age: t * 20 });
    const l = engine.lightNear(p.clone().add(v3(0, 0.5, 0)));
    cast.steve.setLight(l[0], l[1]);
    cast.elytra.setLight(l[0], l[1]);
    const camPos = lift(p.clone().add(v3(2.2, 1.1, -5.4)));
    look(cam, camPos, p.clone().add(v3(-1.4, 0.3, 0)), 50);
    F.particleTime = t;
    F.draw = capt(lt, 'Elytra crashes', 0.25, 1.35);
  }, () => particles.petals(46, 52, S(1465, 92, 141), 6, 5, 50, 31));
  shot('elytra-crash', T_CRASH, 50.0, (F, lt, t) => {
    world(F, { time: t, focus: EP, shadowSize: 30 });
    cast.elytra.holder.visible = false;
    showRagdoll(cast.steve, sims.elytra, lt * 20);
    look(cam, EP.clone().add(v3(2.6, 1.0, 3.4)), sims.elytra.com(lt * 20 + 2).add(v3(0, 0.3, 0)), 42);
    shake(cam, t, Math.max(0, 0.7 - lt * 1.4), 0.25);
    F.particleTime = t;
    F.draw = capt(lt + (T_CRASH - 47.0), 'Elytra crashes', 0.25, 2.85);
  }, () => particles.petals(46, 52, S(1465, 92, 141), 6, 5, 50, 31));
  cue(47.0, 'item/elytra/elytra_loop', 0.5);
  cue(T_CRASH, 'damage/hit', 1.0, 1.0, { random: 2 });
  cue(T_CRASH, 'sfx/thud', 0.8);

  // Going limp on purpose -------------------------------------------------------------------
  const GP = S(...M.limp);
  const T_LIMP = 50.8;
  const limpWalk = walker([[M.limp[0] - 3, M.limp[2] + 3], [M.limp[0], M.limp[2]]], T_LIMP - Math.hypot(3, 3) / 4.317, { duration: 3 });
  const limpCam = solver.find([GP.clone().add(v3(0, 1, 0)), sims.limp.com(40), sims.limp.com(90)], { dist: [5, 8], elev: 14, az: 75, azWeight: 0.05, prefDist: 6.5, clearance: 0.7 });
  shot('limp', 50.0, 53.0, (F, lt, t) => {
    world(F, { time: t, focus: GP.clone().add(v3(2, -2, -2)), shadowSize: 30 });
    if (t < T_LIMP) {
      cast.steve.hold(cast.items.pickaxe);
      poseWalker(cast.steve, limpWalk(t));
    } else {
      cast.steve.hold(cast.items.pickaxe);
      showRagdoll(cast.steve, sims.limp, (t - T_LIMP) * 20);
    }
    const c = t < T_LIMP ? GP : sims.limp.com((t - T_LIMP) * 20);
    look(cam, limpCam.clone().add(v3(lt * 0.15, 0, -lt * 0.1)), c.clone().add(v3(0, 0.8, 0)), 48);
    F.draw = capt(lt, 'Go limp on command', 0.3, 2.85);
  });

  // A zombie leaves a body ------------------------------------------------------------------
  const ZP = S(...M.zombie);
  const steveZ = ZP.clone().add(v3(0, 0, -2.8));
  const T_HIT1 = 54.0, T_KILL = 54.7;
  const zombieWalk = walker([[M.zombie[0], M.zombie[2] + 3.2], [M.zombie[0], M.zombie[2]]], 53.0 - 0.2, { speed: 3.4, duration: 3, steps: false });
  const zombieCam = solver.find([ZP.clone().add(v3(0, 1.2, -1.4)), sims.zombie.com(30)], { dist: [5.5, 8], elev: 10, az: -10, azWeight: 0.05, prefDist: 6.5, clearance: 0.7 });
  const attack = (t, at) => (t >= at - 0.12 && t < at + 0.18 ? clamp01((t - (at - 0.12)) / 0.3) : 0);
  shot('zombie', 53.0, 56.0, (F, lt, t) => {
    world(F, { time: t, focus: ZP, shadowSize: 30 });
    cast.steve.group.visible = true;
    cast.steve.hold(cast.items.sword);
    cast.steve.pose({ x: steveZ.x, y: steveZ.y, z: steveZ.z, bodyYaw: 0, headPitch: 6, heldPose: 'item', age: t * 20,
      attack: attack(t, T_HIT1) || attack(t, T_KILL) });
    cast.steve.setLight(15, 0);
    if (t < T_KILL) {
      const w = zombieWalk(t);
      const kb = t > T_HIT1 ? Math.min((t - T_HIT1) * 6, 0.5) * Math.exp(-(t - T_HIT1) * 3) : 0;
      const zp = S(w.x, w.y, w.z + kb);
      cast.zombie.group.visible = true;
      cast.zombie.pose({ x: zp.x, y: zp.y, z: zp.z, bodyYaw: 180, limbSwing: w.ls, limbAmount: w.la, age: t * 20,
        hurt: t > T_HIT1 && t < T_HIT1 + 0.5 ? 1 : 0 });
      cast.zombie.setLight(15, 0);
    } else {
      showRagdoll(cast.zombie, sims.zombie, (t - T_KILL) * 20);
    }
    const aim = damped((tt) => (tt < T_KILL ? ZP.clone().add(v3(0, 1.0, -1.2)) : sims.zombie.com((tt - T_KILL) * 20).add(v3(0, 0.4, 0))), 53.0, t, 4.5);
    look(cam, zombieCam, aim, 52);
    F.particleTime = t;
    F.draw = capt(lt, 'Mobs leave bodies too', 0.3, 2.85);
  }, () => { particles.crit(T_HIT1, ZP.clone().add(v3(0, 1.4, -0.3)), 10, 41); particles.crit(T_KILL, ZP.clone().add(v3(0, 1.4, -0.3)), 14, 42); });
  cue(53.15, 'mob/zombie/say', 0.7, 1.0, { random: 1 });
  cue(T_HIT1, 'entity/player/attack/strong', 0.8, 1.0, { random: 1 });
  cue(T_HIT1 + 0.02, 'mob/zombie/hurt', 0.8, 1.0, { random: 1 });
  cue(T_KILL, 'entity/player/attack/crit', 0.9, 1.0, { random: 1 });
  cue(T_KILL + 0.02, 'mob/zombie/death', 0.9);

  // Drag the body ---------------------------------------------------------------------------
  const handEv = sims.zombie.events.filter((e) => e.kind === 'hand');
  const handAt = (tick) => {
    if (!handEv.length) return ZP.clone();
    let a = handEv[0], b = handEv[handEv.length - 1];
    for (let i = 1; i < handEv.length; i++) if (handEv[i].tick >= tick) { a = handEv[i - 1]; b = handEv[i]; break; }
    const u = b.tick === a.tick ? 0 : clamp01((tick - a.tick) / (b.tick - a.tick));
    return a.pos.clone().lerp(b.pos, u);
  };
  const T_DRAG0 = 56.0, DRAG_TICK0 = 76;
  shot('drag', 56.0, 59.0, (F, lt, t) => {
    const tick = DRAG_TICK0 + lt * 20;
    world(F, { time: t, focus: sims.zombie.com(tick), shadowSize: 30 });
    showRagdoll(cast.zombie, sims.zombie, tick);
    // The grip point is 2 blocks out from the eyes, so Steve walks backwards, eyes on the body.
    const h = handAt(tick);
    const feet = h.clone().add(v3(-1.97, -1.45, 0.05));
    const hPrev = handAt(tick - 1);
    const speed = h.distanceTo(hPrev) * 20;
    cast.steve.group.visible = true;
    cast.steve.hold(null);
    cast.steve.pose({ x: feet.x, y: ZP.y, z: feet.z, bodyYaw: -90, headPitch: 8, age: t * 20,
      limbSwing: -tick * 0.55 * Math.min(speed / 1.6, 1), limbAmount: Math.min(speed / 4.3 * 1.2, 0.6),
      override: { rightArm: [-1.25, 0, 0] } });
    cast.steve.setLight(15, 0);
    const mid = feet.clone().lerp(sims.zombie.com(tick), 0.5);
    look(cam, lift(mid.clone().add(v3(0.6, 1.7, -6.8))), mid.clone().add(v3(0, 0.6, 0)), 46);
    F.draw = capt(lt, 'Drag them around', 0.3, 2.85);
  });
  for (let k = 0; k < 7; k++) cue(56.2 + k * 0.42, 'step/grass', 0.25, 1.0, { random: k });

  // ==========================================================================================
  // 18-20. THE RETURN  59.0 - 72.0   He comes back for his stuff.
  // ==========================================================================================
  const corpse = sims.corpse;
  const CORPSE_TICK = 199;
  const FP = S(...M.flop);
  const returnWalk = walker([[1506.5, 147.5], [1506.5, 144.5], [1505.2, 141.5], [1504.0, 139.2], [M.flop[0], M.flop[2]]], 59.0,
    { y0: 23.4, duration: 6, yawEnd: -60, stepSound: 'step/stone', stepVol: 0.4 });
  const returnCam = solver.find([corpse.com(CORPSE_TICK).add(v3(0, 0.3, 0)), FP.clone().add(v3(0, 1.4, 0)), S(1505.6, 23, 143.0), S(1506.5, 24.5, 147)],
    { dist: [5, 8], elev: 24, az: -115, azWeight: 0.04, prefDist: 6, fluidWeight: 3 });
  const cave = (F, t, o = {}) => world(F, Object.assign({ time: t, blasted: true, caveFog: 0.25, focus: FP }, o));
  shot('return', 59.0, 63.0, (F, lt, t) => {
    cave(F, t);
    cast.body.group.visible = true;
    showRagdoll(cast.body, corpse, CORPSE_TICK);
    cast.steve.hold(null);
    poseWalker(cast.steve, returnWalk(t), { headPitch: lerp(10, 30, ease.inOut(range(lt, 2.4, 3.4))) });
    const w = returnWalk(t);
    look(cam, returnCam, S(w.x, w.y + 1.1, w.z).lerp(corpse.com(CORPSE_TICK), 0.5), 56);
    F.post = { aperture: 0.03, focus: returnCam.distanceTo(FP) };
    F.draw = (g, ov) => ov.caption(g, 'Your body stays where you fell.', fade(lt, 0.6, 3.85, 0.25, 0.3), '#ffffff', true);
  });

  // POV: open the body, then shift-click everything back.
  const CONTAINER = [...HOTBAR, null, { name: 'dirt|dirt|dirt', count: 37 }, { name: 'item/coal', count: 11 }, { name: 'item/raw_iron', count: 5 },
    { name: 'item/stick', count: 9 }, { name: 'item/wheat_seeds', count: 3 }, { name: 'cherry_planks|cherry_planks|cherry_planks', count: 18 }];
  const T_OPEN = 63.55, T_CLOSE = 65.35, T_EQUIP = 65.7;
  shot('loot', 63.0, 67.0, (F, lt, t) => {
    cave(F, t);
    const eye = FP.clone().add(v3(0, 1.62, 0));
    const looted = t >= T_EQUIP;
    if (!looted) { cast.body.group.visible = true; showRagdoll(cast.body, corpse, CORPSE_TICK); }
    cast.steve.group.visible = false;
    look(cam, eye, corpse.com(CORPSE_TICK).add(v3(0, 0.1, 0)), 70);
    F.fp = looted;
    if (looted) {
      fp.root.position.set(0.56, -0.52 - Math.max(0, 1 - (t - T_EQUIP) * 4) * 0.6, -0.72);
      fp.attack.rotation.set(0, 0, 0);
      const l = engine.lightNear(eye);
      cast.items.pickaxeFP.mat.uniforms.lightLevel.value.set(l[0], l[1]);
    }
    const hot = HOTBAR.map((it, i) => (looted && t >= T_EQUIP + i * 0.06 ? it : null));
    const pop = HOTBAR.map((_, i) => Math.max(0, 1 - (t - (T_EQUIP + i * 0.06)) * 5) * (looted ? 1 : 0));
    F.draw = (g, ov) => {
      ov.hud(g, { hotbar: hot, pop, selected: 0, health: 20, food: 20, xp: 0, level: 0, crosshair: t < T_OPEN || t > T_CLOSE });
      if (t >= T_OPEN && t < T_CLOSE) {
        const hoverSeq = [0, 2, 4, 5, 11];
        const k = Math.min(hoverSeq.length - 1, Math.floor((t - T_OPEN - 0.25) / 0.3));
        const slots = ov.chestScreen(g, { title: 'Steve\'s Body', container: CONTAINER, inventory: [], hotbar: [], hoverSlot: t - T_OPEN > 0.25 ? hoverSeq[k] : -1 });
        const sp = slots[hoverSeq[Math.max(k, 0)]];
        if (t - T_OPEN > 0.2) ov.cursor(g, sp[0] + 10, sp[1] + 9);
      }
      if (t < T_OPEN) ov.caption(g, 'Your body stays where you fell.', fade(lt, -1, 0.5, 0.2, 0.3), '#ffffff', true);
      if (t > T_CLOSE) ov.caption(g, 'So does your stuff.', fade(t, T_CLOSE + 0.15, 66.95, 0.25, 0.3));
    };
  });
  cue(T_OPEN, 'random/chestopen', 0.6);
  cue(T_CLOSE, 'random/chestclosed', 0.5);
  for (let i = 0; i < 8; i++) cue(T_EQUIP + i * 0.06, 'random/pop', 0.35, 1.2 + i * 0.08);

  // The button: he looks up the shaft... and lies down anyway.
  const T_FLOP = 69.75;
  const flopCamA = lift(FP.clone().add(v3(1.0, 0.3, 0.75)), 0.25);
  const shaftTop = S(1503.2, 84, 137.2);
  const flopCamB = solver.find([FP.clone().add(v3(0.6, 0.9, 0)), sims.flop.com(80)], { dist: [3, 5], elev: 10, elevWeight: 0.15, az: 25, azWeight: 0.05, prefDist: 3.8, fluidWeight: 3 });
  shot('look-up', 67.0, 69.0, (F, lt, t) => {
    cave(F, t);
    cast.steve.hold(cast.items.pickaxe);
    cast.steve.group.visible = true;
    cast.steve.pose({ x: FP.x, y: FP.y, z: FP.z, bodyYaw: -60, headPitch: lerp(20, -62, ease.inOut(range(lt, 0.3, 1.2))), heldPose: 'item', age: t * 20 });
    const l = engine.lightNear(FP.clone().add(v3(0, 1, 0)));
    cast.steve.setLight(l[0], l[1]);
    look(cam, flopCamA, FP.clone().add(v3(0, 1.9, 0)).lerp(shaftTop, 0.04 + ease.inOut(range(lt, 0.5, 1.8)) * 0.5), 74);
  });
  shot('flop', 69.0, 72.0, (F, lt, t) => {
    cave(F, t);
    cast.steve.hold(cast.items.pickaxe);
    if (t < T_FLOP) {
      cast.steve.group.visible = true;
      cast.steve.pose({ x: FP.x, y: FP.y, z: FP.z, bodyYaw: -60, headPitch: lerp(-30, 8, ease.inOut(range(lt, 0.0, 0.35))),
        headYaw: lerp(0, 35, ease.inOut(range(lt, 0.1, 0.45))), heldPose: 'item', age: t * 20 });
      const l = engine.lightNear(FP.clone().add(v3(0, 1, 0)));
      cast.steve.setLight(l[0], l[1]);
    } else {
      showRagdoll(cast.steve, sims.flop, (t - T_FLOP) * 20);
    }
    look(cam, flopCamB, FP.clone().add(v3(0.4, t < T_FLOP ? 1.1 : 0.5, 0)), 44);
    F.draw = (g, ov) => ov.caption(g, 'Get back up. Or don\'t.', fade(t, T_FLOP + 0.55, 71.95, 0.3, 0.3));
  });
  cue(T_FLOP + 0.5, 'sfx/flop', 0.6);

  // ==========================================================================================
  // 21-22. END CARD and STINGER  72.0 - 83.0
  // ==========================================================================================
  const T_HISS = 79.4, T_END_BOOM = 80.9;
  const flyAway = (i, t) => {
    // After the blast every letter is thrown outward, spinning, under gravity.
    const dt = t - T_END_BOOM;
    const ang = [-2.6, -2.1, -1.7, -1.3, -0.9, -0.5][i];
    const v = 1100 + i * 90;
    return { dx: Math.cos(ang) * v * dt, dy: Math.sin(ang) * v * dt + 1500 * dt * dt, rot: (i % 2 ? 1 : -1) * dt * (420 + i * 80), a: 1 };
  };
  shot('end-card', 72.0, 83.0, (F, lt, t) => {
    look(cam, v3(0, -300, 0), v3(0, -301, 0), 70);
    world(F, {});
    const boomK = t >= T_END_BOOM ? Math.max(0, 1 - (t - T_END_BOOM) / 0.5) : 0;
    F.post = { fade: 1 };
    F.draw = (g, ov) => {
      const bg = '#121316';
      g.fillStyle = bg;
      g.fillRect(0, 0, ov.w, ov.h);
      const tremble = t >= T_HISS && t < T_END_BOOM ? (t - T_HISS) / (T_END_BOOM - T_HISS) : 0;
      const letters = [0, 1, 2, 3, 4, 5].map((i) => {
        if (t >= T_END_BOOM) return flyAway(i, t);
        const d = letterDrop(t, 72.25, i, i + 1);
        if (tremble > 0) { d.dx += noise1(t * 40 + i * 7) * 9 * tremble; d.dy += noise1(t * 43 + i * 13) * 9 * tremble; d.rot += noise1(t * 37 + i) * 5 * tremble; }
        return d;
      });
      const textA = t < T_END_BOOM ? 1 : Math.max(0, 1 - (t - T_END_BOOM) * 4);
      ov.logo(g, ov.w / 2, ov.h * 0.4, ov.h * 0.9, letters);
      const s = ov.h / 1080;
      ov.plain(g, 'Ragdoll physics for players and mobs.', ov.w / 2, ov.h * 0.72, 46 * s, '#e8eaef', 'PlexR', 'center', clamp01((t - 73.5) / 0.5) * textA);
      ov.plain(g, 'Forge 1.20.1   ·   Server-side   ·   No native libraries', ov.w / 2, ov.h * 0.79, 30 * s, '#9aa0aa', 'PlexR', 'center', clamp01((t - 74.3) / 0.5) * textA);
      ov.plain(g, 'github.com/ShrimpScript/Tumble', ov.w / 2, ov.h * 0.88, 36 * s, 'rgb(224,96,58)', 'PlexSB', 'center', clamp01((t - 75.1) / 0.5) * textA);
      if (boomK > 0) { g.fillStyle = `rgba(255,255,255,${boomK})`; g.fillRect(0, 0, ov.w, ov.h); }
      const out = clamp01((t - 82.3) / 0.5);
      if (out > 0) { g.fillStyle = `rgba(0,0,0,${out})`; g.fillRect(0, 0, ov.w, ov.h); }
    };
  });
  for (let i = 0; i < 6; i++) cue(72.25 + i * 0.12 + 0.32, 'sfx/letter', 0.5, 0.9 + i * 0.05);
  cue(T_HISS, 'random/fuse', 1.0);
  cue(T_END_BOOM, 'random/explode', 1.0, 1.0, { random: 3 });


  const duration = shots[shots.length - 1].end;
  let active = null;
  return {
    duration, shots, cues,
    frameState(t) {
      const s = shots.find((x) => t >= x.start && t < x.end) || shots[shots.length - 1];
      if (s !== active) {
        particles.clear();
        if (s.setup) s.setup();
        active = s;
      }
      for (const k of ['steve', 'body', 'zombie', 'creeper']) cast[k].group.visible = false;
      cast.steve.hurt(0);
      cast.steve.hold(cast.items.pickaxe);
      cast.elytra.holder.visible = false;
      cast.rain.group.visible = false;
      U.daylight.value = 1;
      cast.creeper.mat.uniforms.whiteFlash.value = 0;
      const F = { ticks: t * 20, particleTime: t, post: {}, render: {}, fp: false };
      s.fn(F, t - s.start, t);
      return F;
    },
  };
}

// Steve's hotbar for the morning (and what ends up in his body).
export const HOTBAR = [
  { name: 'item/iron_pickaxe', count: 1 }, { name: 'item/iron_sword', count: 1 }, { name: 'block/torch', count: 23 },
  { name: 'cherry_log_top|cherry_log|cherry_log', count: 14 }, { name: 'cobblestone|cobblestone|cobblestone', count: 46 },
  { name: 'item/bread', count: 6 }, { name: 'crafting_table_top|crafting_table_front|crafting_table_side', count: 1 },
  null, { name: 'item/apple', count: 3 },
];
