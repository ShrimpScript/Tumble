// Weather effects: vanilla-style rain columns around the camera and a lightning bolt
// built like LightningBoltRenderer (stacked jagged segments in four widening layers).
import * as THREE from 'three';
import { mcTexture } from './entities.js';

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6D2B79F5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export class Rain {
  constructor(assetBase) {
    const tex = mcTexture(assetBase + 'textures/environment/rain.png');
    tex.wrapS = THREE.RepeatWrapping;
    tex.wrapT = THREE.RepeatWrapping;
    tex.needsUpdate = true;
    this.mat = new THREE.ShaderMaterial({
      uniforms: { map: { value: tex }, time: { value: 0 }, bright: { value: 0.6 } },
      vertexShader: `attribute float offset; varying vec2 vUv; varying float vA; uniform float time;
        void main(){ vUv = vec2(uv.x * 0.25, uv.y * 4.0 + time * 3.2 + offset); vA = 1.0; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
      fragmentShader: `uniform sampler2D map; uniform float bright; varying vec2 vUv;
        void main(){ vec4 t = texture2D(map, vUv); if (t.a < 0.05) discard; gl_FragColor = vec4(t.rgb * bright, t.a * 0.75); }`,
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    this.mesh = null;
    this.group = new THREE.Group();
  }

  // One quad per column within `radius` of the camera, from the ground up past the camera.
  build(center, groundAt, radius = 10) {
    if (this.mesh) { this.group.remove(this.mesh); this.mesh.geometry.dispose(); }
    const pos = [], uv = [], off = [];
    const r = rng(77);
    const cx = Math.floor(center.x), cz = Math.floor(center.z);
    for (let dx = -radius; dx <= radius; dx++) {
      for (let dz = -radius; dz <= radius; dz++) {
        if (dx * dx + dz * dz > radius * radius) continue;
        const x = cx + dx + 0.5, z = cz + dz + 0.5;
        const g = groundAt(x, z);
        const top = center.y + 12;
        if (g >= top) continue;
        // Face the camera column-wise, like the vanilla quad orientation.
        const ang = Math.atan2(dz, dx) + Math.PI / 2;
        const ox = Math.cos(ang) * 0.5, oz = Math.sin(ang) * 0.5;
        const o = r() * 8;
        const quad = [[x - ox, g, z - oz, 0, (top - g) / 4], [x + ox, g, z + oz, 1, (top - g) / 4], [x + ox, top, z + oz, 1, 0], [x - ox, top, z - oz, 0, 0]];
        for (const k of [0, 1, 2, 0, 2, 3]) {
          pos.push(quad[k][0], quad[k][1], quad[k][2]);
          uv.push(quad[k][3], quad[k][4]);
          off.push(o);
        }
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setAttribute('offset', new THREE.Float32BufferAttribute(off, 1));
    this.mesh = new THREE.Mesh(g, this.mat);
    this.mesh.frustumCulled = false;
    this.mesh.renderOrder = 15;
    this.group.add(this.mesh);
  }
}

// LightningBoltRenderer: a main bolt of 8 segments from high above, with branches, drawn
// as additive translucent quads in four layers of growing width.
export function makeBolt(target, seed = 4) {
  const r = rng(seed);
  const pos = [];
  const col = [];
  const segs = [];
  const H = 96;
  let x = 0, z = 0;
  const pts = [[0, 0]];
  for (let i = 1; i <= 8; i++) {
    x += (r() - 0.5) * 7;
    z += (r() - 0.5) * 7;
    pts.push([x, z]);
  }
  // The bolt ends on the target.
  const endX = pts[8][0], endZ = pts[8][1];
  const main = pts.map(([px, pz], i) => [px - endX * (i / 8), H - (H / 8) * i, pz - endZ * (i / 8)]).reverse();
  segs.push(main);
  for (let b = 0; b < 3; b++) {
    const start = 2 + Math.floor(r() * 4);
    let [bx, by, bz] = main[start];
    const branch = [[bx, by, bz]];
    for (let i = 0; i < 3 + Math.floor(r() * 3); i++) {
      bx += (r() - 0.5) * 6; by -= H / 12; bz += (r() - 0.5) * 6;
      branch.push([bx, by, bz]);
    }
    segs.push(branch);
  }
  for (const s of segs) {
    for (let layer = 0; layer < 4; layer++) {
      const w = 0.1 + layer * 0.1;
      for (let i = 0; i < s.length - 1; i++) {
        const a = s[i], b = s[i + 1];
        for (const [ox, oz] of [[w, 0], [0, w]]) {
          const quad = [[a[0] - ox, a[1], a[2] - oz], [a[0] + ox, a[1], a[2] + oz], [b[0] + ox, b[1], b[2] + oz], [b[0] - ox, b[1], b[2] - oz]];
          for (const k of [0, 1, 2, 0, 2, 3]) {
            pos.push(quad[k][0], quad[k][1], quad[k][2]);
            col.push(0.45, 0.45, 0.5);
          }
        }
      }
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  const m = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.3,
    blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide }));
  m.position.copy(target);
  m.frustumCulled = false;
  m.renderOrder = 30;
  return m;
}
