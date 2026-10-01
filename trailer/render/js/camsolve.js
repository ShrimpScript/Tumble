// Camera placement against the real world: occupancy lookups, clearance, line of sight,
// and a small search that finds a camera position able to see a set of subject points
// (used for the cave shots, where hand-placed cameras end up inside rock).
import * as THREE from 'three';

export class CamSolver {
  constructor(occ, meta, sceneOrigin) {
    this.occ = occ;
    this.o = meta.origin;
    this.s = meta.size;
    this.so = sceneOrigin;
  }

  // 0 open, 1 solid, 2 fluid. Outside the grid counts as open.
  at(p) {
    const x = Math.floor(p.x + this.so[0]) - this.o[0];
    const y = Math.floor(p.y + this.so[1]) - this.o[1];
    const z = Math.floor(p.z + this.so[2]) - this.o[2];
    if (x < 0 || y < 0 || z < 0 || x >= this.s[0] || y >= this.s[1] || z >= this.s[2]) return 0;
    return this.occ[(x * this.s[1] + y) * this.s[2] + z];
  }

  clear(p, r = 0.45) {
    const v = new THREE.Vector3();
    for (const dx of [-r, 0, r]) for (const dy of [-r, 0, r]) for (const dz of [-r, 0, r]) {
      if (this.at(v.set(p.x + dx, p.y + dy, p.z + dz)) !== 0) return false;
    }
    return true;
  }

  // Counts of solid and fluid samples on the segment a -> b, ignoring `skip` blocks at b.
  los(a, b, skip = 0.5, step = 0.12) {
    const d = b.clone().sub(a);
    const len = d.length();
    d.divideScalar(len);
    const p = new THREE.Vector3();
    let solid = 0, fluid = 0;
    for (let s = 0.2; s < len - skip; s += step) {
      const v = this.at(p.copy(a).addScaledVector(d, s));
      if (v === 1) solid++;
      else if (v === 2) fluid++;
    }
    return { solid, fluid };
  }

  /**
   * Finds a camera for subject points `targets` (scene space). Preferences: `dist`
   * (range), `elev` and `az` (degrees, az 0 = +x, 90 = +z), each with a weight.
   */
  find(targets, opts = {}) {
    const centre = new THREE.Vector3();
    for (const t of targets) centre.add(t);
    centre.divideScalar(targets.length);
    const [d0, d1] = opts.dist ?? [3, 7];
    let best = null;
    const p = new THREE.Vector3();
    for (let el = -70; el <= 80; el += 7.5) {
      for (let az = 0; az < 360; az += 7.5) {
        for (let d = d0; d <= d1 + 1e-6; d += 0.5) {
          const e = (el * Math.PI) / 180, a = (az * Math.PI) / 180;
          p.set(Math.cos(e) * Math.cos(a), Math.sin(e), Math.cos(e) * Math.sin(a)).multiplyScalar(d).add(centre);
          if (!this.clear(p, opts.clearance ?? 0.45)) continue;
          let solid = 0, fluid = 0;
          for (const t of targets) {
            const r = this.los(p, t, opts.skip ?? 0.45);
            solid += r.solid;
            fluid += r.fluid;
          }
          if (solid > (opts.allowSolid ?? 0)) continue;
          let score = -fluid * (opts.fluidWeight ?? 2);
          if (opts.elev !== undefined) score -= Math.abs(el - opts.elev) * (opts.elevWeight ?? 0.08);
          if (opts.az !== undefined) score -= Math.abs(((az - opts.az + 540) % 360) - 180) * (opts.azWeight ?? 0.05);
          if (opts.prefDist !== undefined) score -= Math.abs(d - opts.prefDist) * (opts.distWeight ?? 0.6);
          if (!best || score > best.score) best = { pos: p.clone(), score, el, az, d, fluid };
        }
      }
    }
    if (!best) console.log('camsolve: no camera found near', centre.toArray().map((v) => v.toFixed(1)).join(','));
    return best ? best.pos : centre.clone().add(new THREE.Vector3(0, 3, 0));
  }
}
