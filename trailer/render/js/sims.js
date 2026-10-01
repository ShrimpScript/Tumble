// Playback of TrailerSim output: six bodies per tick, interpolated like the client's
// PoseBuffer (lerp positions, slerp rotations between consecutive server ticks).
import * as THREE from 'three';

const MASS = [5, 30, 4, 4, 10, 10];
const qa = new THREE.Quaternion(), qb = new THREE.Quaternion();

export async function loadSim(base, name, sceneOrigin) {
  const buf = await (await fetch(`${base}${name}.f32`)).arrayBuffer();
  const data = new Float32Array(buf);
  const text = await (await fetch(`${base}${name}.events.txt`)).text();
  const events = text.split('\n').slice(1).filter(Boolean).map((l) => {
    const p = l.split(' ');
    const e = { tick: +p[0], kind: p[1] };
    const at = l.indexOf(' at ');
    if (at > 0) {
      const xyz = l.slice(at + 4).split(' ').map(Number);
      e.pos = new THREE.Vector3(xyz[0] - sceneOrigin[0], xyz[1] - sceneOrigin[1], xyz[2] - sceneOrigin[2]);
    }
    if (p[1] === 'hurt') { e.damage = +p[2]; e.impact = +p[4]; }
    if (p[1] === 'bump') e.impact = +p[3];
    return e;
  });
  const frames = data.length / 42;
  const so = sceneOrigin;
  return {
    name, frames, events,
    // Bodies at a fractional tick, in scene coordinates.
    sample(tick, out = []) {
      const t = Math.min(Math.max(tick, 0), frames - 1);
      const i = Math.min(Math.floor(t), frames - 2);
      const f = t - i;
      for (let k = 0; k < 6; k++) {
        const a = (i * 6 + k) * 7, b = ((i + 1) * 6 + k) * 7;
        qa.set(data[a + 3], data[a + 4], data[a + 5], data[a + 6]);
        qb.set(data[b + 3], data[b + 4], data[b + 5], data[b + 6]);
        qa.slerp(qb, f);
        out[k] = [
          data[a] + (data[b] - data[a]) * f - so[0],
          data[a + 1] + (data[b + 1] - data[a + 1]) * f - so[1],
          data[a + 2] + (data[b + 2] - data[a + 2]) * f - so[2],
          qa.x, qa.y, qa.z, qa.w,
        ];
      }
      return out;
    },
    com(tick) {
      const b = this.sample(tick);
      const c = new THREE.Vector3();
      for (let k = 0; k < 6; k++) c.add(new THREE.Vector3(b[k][0], b[k][1], b[k][2]).multiplyScalar(MASS[k]));
      return c.multiplyScalar(1 / 63);
    },
    part(tick, k) {
      const b = this.sample(tick);
      return new THREE.Vector3(b[k][0], b[k][1], b[k][2]);
    },
  };
}
