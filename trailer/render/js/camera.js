// Camera rigs: eased keyframe paths, damped follow, trauma shake and dolly zoom.
import * as THREE from 'three';

export const ease = {
  linear: (t) => t,
  inOut: (t) => t * t * (3 - 2 * t),
  inOut5: (t) => t * t * t * (t * (t * 6 - 15) + 10),
  out: (t) => 1 - (1 - t) * (1 - t),
  in: (t) => t * t,
  outCubic: (t) => 1 - Math.pow(1 - t, 3),
  inCubic: (t) => t * t * t,
};

export const clamp01 = (x) => Math.min(1, Math.max(0, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const v3 = (x, y, z) => new THREE.Vector3(x, y, z);
export const range = (t, a, b) => clamp01((t - a) / (b - a));

// Centripetal Catmull-Rom through points, parameterised 0..1.
export function path(points, closed = false) {
  const c = new THREE.CatmullRomCurve3(points.map((p) => (p.isVector3 ? p : v3(...p))), closed, 'centripetal');
  return (t) => c.getPoint(clamp01(t));
}

// Smooth value noise for shake, deterministic.
function hash(n) { const s = Math.sin(n * 127.1) * 43758.5453; return s - Math.floor(s); }
export function noise1(x) {
  const i = Math.floor(x), f = x - i;
  const u = f * f * (3 - 2 * f);
  return lerp(hash(i), hash(i + 1), u) * 2 - 1;
}

/** Trauma shake (Eiserloh): offset and roll scale with trauma squared. */
export function shake(cam, time, trauma, amount = 0.35, freq = 18) {
  const s = Math.max(0, Math.min(1, trauma)) ** 2;
  if (s <= 0) return;
  const right = new THREE.Vector3().setFromMatrixColumn(cam.matrixWorld, 0);
  const up = new THREE.Vector3().setFromMatrixColumn(cam.matrixWorld, 1);
  cam.position.addScaledVector(right, noise1(time * freq + 11) * amount * s);
  cam.position.addScaledVector(up, noise1(time * freq + 57) * amount * s);
  cam.rotateZ(noise1(time * freq + 91) * 0.06 * s);
  cam.rotateX(noise1(time * freq + 23) * 0.03 * s);
}

/**
 * Critically damped spring follow, integrated from the start of the shot so any frame
 * can be rendered on its own and still match its neighbours exactly.
 */
export function damped(targetFn, t0, t, omega = 4, step = 1 / 120) {
  let x = targetFn(t0).clone();
  let v = new THREE.Vector3();
  const tmp = new THREE.Vector3();
  for (let s = t0; s < t; s += step) {
    const h = Math.min(step, t - s);
    const target = targetFn(s + h);
    // x'' = -2w x' - w^2 (x - target)
    tmp.copy(x).sub(target).multiplyScalar(-omega * omega).addScaledVector(v, -2 * omega);
    v.addScaledVector(tmp, h);
    x.addScaledVector(v, h);
  }
  return x;
}

export function look(cam, pos, target, fov, roll = 0) {
  cam.position.copy(pos);
  cam.up.set(0, 1, 0);
  cam.lookAt(target);
  if (roll) cam.rotateZ(roll);
  if (fov) { cam.fov = fov; cam.updateProjectionMatrix(); }
  cam.updateMatrixWorld();
}

/** Dolly zoom: keeps a subject at distance d0 the same size while the FOV changes. */
export function dollyDistance(d0, fov0, fov) {
  return d0 * Math.tan((fov0 * Math.PI) / 360) / Math.tan((fov * Math.PI) / 360);
}
