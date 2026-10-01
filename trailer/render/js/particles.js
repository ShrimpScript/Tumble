// Billboard particles using the game's own particle sprites, evaluated purely from
// time so every frame can be rendered independently. Each kind follows the vanilla
// particle class it imitates (lifetime, size, friction, gravity, sprite-from-age).
import * as THREE from 'three';
import { LIGHTMAP_GLSL, FOG_GLSL } from './shaderlib.js';
import { sharedUniforms } from './terrain.js';

const SPRITES = {
  explosion: Array.from({ length: 16 }, (_, i) => `explosion_${i}`),
  generic: Array.from({ length: 8 }, (_, i) => `generic_${i}`),
  big_smoke: Array.from({ length: 12 }, (_, i) => `big_smoke_${i}`),
  cherry: Array.from({ length: 12 }, (_, i) => `cherry_${i}`),
  flame: ['flame'],
  critical_hit: ['critical_hit'],
  damage: ['damage'],
};

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

export class Particles {
  constructor(assetBase) {
    this.assetBase = assetBase;
    this.list = [];
    this.max = 4096;
    this.layerOf = {};
  }

  async load() {
    const S = 32;
    const names = Object.values(SPRITES).flat();
    const data = new Uint8Array(S * S * 4 * names.length);
    const c = document.createElement('canvas');
    c.width = S; c.height = S;
    const ctx = c.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    for (let i = 0; i < names.length; i++) {
      const img = new Image();
      img.src = `${this.assetBase}textures/particle/${names[i]}.png`;
      await img.decode();
      ctx.clearRect(0, 0, S, S);
      ctx.drawImage(img, 0, 0, img.width, img.width, 0, 0, S, S);
      data.set(ctx.getImageData(0, 0, S, S).data, i * S * S * 4);
      this.layerOf[names[i]] = i;
    }
    const tex = new THREE.DataArrayTexture(data, S, S, names.length);
    tex.magFilter = THREE.NearestFilter;
    tex.minFilter = THREE.NearestFilter;
    tex.needsUpdate = true;
    tex.flipY = false;

    const g = new THREE.InstancedBufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute([-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0], 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute([0, 1, 1, 1, 1, 0, 0, 0], 2));
    g.setIndex([0, 1, 2, 0, 2, 3]);
    this.iPos = new THREE.InstancedBufferAttribute(new Float32Array(this.max * 4), 4);   // xyz, size
    this.iCol = new THREE.InstancedBufferAttribute(new Float32Array(this.max * 4), 4);   // rgb, alpha
    this.iMisc = new THREE.InstancedBufferAttribute(new Float32Array(this.max * 4), 4);  // layer, sky, block, roll
    g.setAttribute('iPos', this.iPos);
    g.setAttribute('iCol', this.iCol);
    g.setAttribute('iMisc', this.iMisc);
    this.geometry = g;
    this.material = new THREE.ShaderMaterial({
      glslVersion: THREE.GLSL3,
      uniforms: Object.assign({ sprites: { value: tex } }, sharedUniforms),
      vertexShader: /* glsl */`
        in vec4 iPos; in vec4 iCol; in vec4 iMisc;
        out vec2 vUv; out vec4 vCol; flat out float vLayer; out vec2 vLight; out vec3 vWorld; out float vDepth;
        void main() {
          vUv = uv; vCol = iCol; vLayer = iMisc.x; vLight = iMisc.yz;
          vec4 mv = viewMatrix * vec4(iPos.xyz, 1.0);
          float c = cos(iMisc.w), s = sin(iMisc.w);
          vec2 p = vec2(position.x * c - position.y * s, position.x * s + position.y * c);
          mv.xy += p * iPos.w;
          vWorld = (inverse(viewMatrix) * mv).xyz;
          vDepth = -mv.z;
          gl_Position = projectionMatrix * mv;
        }`,
      fragmentShader: /* glsl */`
        precision highp float; precision highp sampler2DArray;
        uniform sampler2DArray sprites;
        in vec2 vUv; in vec4 vCol; flat in float vLayer; in vec2 vLight; in vec3 vWorld; in float vDepth;
        out vec4 o;
        ${LIGHTMAP_GLSL}
        ${FOG_GLSL}
        void main() {
          vec4 t = texture(sprites, vec3(vUv, vLayer));
          if (t.a * vCol.a < 0.02) discard;
          vec3 lm = vLight.x < 0.0 ? vec3(1.0) : lightmap(vLight.x, vLight.y, 1.0);
          vec3 c = applyFog(t.rgb * vCol.rgb * lm, vWorld, vDepth);
          o = vec4(c, t.a * vCol.a);
        }`,
      transparent: true,
      depthWrite: false,
    });
    this.mesh = new THREE.Mesh(g, this.material);
    this.mesh.frustumCulled = false;
    this.mesh.renderOrder = 20;
    return this;
  }

  clear() { this.list.length = 0; }

  // HugeExplosionSeedParticle: 8 ticks, 6 HugeExplosionParticles per tick within +-4 blocks.
  explosion(t0, pos, seed = 1) {
    const r = rng(seed);
    for (let tick = 0; tick < 8; tick++) {
      for (let i = 0; i < 6; i++) {
        const life = (6 + Math.floor(r() * 4)) / 20;
        const f = r() * 0.6 + 0.4;
        this.list.push({
          kind: 'explosion', t0: t0 + tick / 20, life,
          pos: pos.clone().add(new THREE.Vector3((r() - r()) * 4, (r() - r()) * 4, (r() - r()) * 4)),
          vel: new THREE.Vector3(), size: 2.0 * (1 - (tick / 8) * 0.5), color: [f, f, f], bright: true,
          animate: true,
        });
      }
    }
    // Vanilla also puffs the creeper's own smoke as it dies; a short-lived cloud sells it.
    for (let i = 0; i < 40; i++) {
      const dir = new THREE.Vector3(r() - 0.5, r() * 0.8, r() - 0.5).normalize();
      this.list.push({
        kind: 'big_smoke', t0: t0 + r() * 0.15, life: 1.6 + r() * 1.4,
        pos: pos.clone().add(dir.clone().multiplyScalar(r() * 1.5)),
        vel: dir.multiplyScalar(2 + r() * 4), drag: 0.9, gravity: -0.6, size: 0.9 + r() * 0.9,
        grow: 0.8, color: [0.85, 0.85, 0.85], alpha: 0.85, animate: true, fade: true,
      });
    }
  }

  // FallingLeavesParticle for cherry leaves: slow drift, sway, a few seconds of life.
  petals(t0, t1, center, radius, height, count, seed = 3) {
    const r = rng(seed);
    for (let i = 0; i < count; i++) {
      const start = t0 + r() * (t1 - t0);
      this.list.push({
        kind: 'cherry', t0: start - 2, life: 6 + r() * 3,
        pos: center.clone().add(new THREE.Vector3((r() - 0.5) * 2 * radius, height * (0.4 + r() * 0.6), (r() - 0.5) * 2 * radius)),
        vel: new THREE.Vector3((r() - 0.5) * 0.6, -0.9 - r() * 0.4, (r() - 0.5) * 0.6),
        sway: 0.6 + r() * 0.4, size: 0.08, frame: Math.floor(r() * 12), color: [1, 1, 1], roll: r() * 6.28,
      });
    }
  }

  // Generic poof (death / dust) puff.
  poof(t0, pos, count = 12, seed = 9, spread = 0.6, color = [1, 1, 1]) {
    const r = rng(seed);
    for (let i = 0; i < count; i++) {
      this.list.push({
        kind: 'generic', t0, life: (8 + r() * 12) / 20,
        pos: pos.clone().add(new THREE.Vector3((r() - 0.5) * spread, (r() - 0.5) * spread, (r() - 0.5) * spread)),
        vel: new THREE.Vector3((r() - 0.5) * 2, r() * 1.5, (r() - 0.5) * 2), drag: 0.85,
        size: 0.1 + r() * 0.08, color, animate: true, reverse: true,
      });
    }
  }

  crit(t0, pos, count = 10, seed = 5) {
    const r = rng(seed);
    for (let i = 0; i < count; i++) {
      this.list.push({
        kind: 'critical_hit', t0, life: 0.4 + r() * 0.3,
        pos: pos.clone(), vel: new THREE.Vector3((r() - 0.5) * 6, r() * 5, (r() - 0.5) * 6), drag: 0.7, gravity: 8,
        size: 0.08, color: [1, 1, 1],
      });
    }
  }

  update(t, engine) {
    let n = 0;
    const P = this.iPos.array, C = this.iCol.array, M = this.iMisc.array;
    for (const p of this.list) {
      const age = t - p.t0;
      if (age < 0 || age > p.life || n >= this.max) continue;
      const k = age / p.life;
      // Integrate per tick with friction, like Particle.tick.
      const ticks = age * 20;
      const fr = p.drag ?? 0.98;
      const pos = p.pos.clone();
      const vel = p.vel.clone().multiplyScalar(1 / 20);
      const whole = Math.floor(ticks);
      for (let i = 0; i < whole; i++) {
        vel.y -= (p.gravity ?? 0) / 400;
        pos.add(vel);
        vel.multiplyScalar(fr);
      }
      pos.addScaledVector(vel, ticks - whole);
      if (p.sway) pos.x += Math.sin(age * 2.2 + p.roll) * 0.3 * p.sway;
      const frames = SPRITES[p.kind];
      let fi = p.frame ?? 0;
      if (p.animate) {
        fi = Math.min(frames.length - 1, Math.floor(k * frames.length));
        if (p.reverse) fi = frames.length - 1 - fi;
      }
      const layer = this.layerOf[frames[fi % frames.length]];
      const size = p.size * (1 + (p.grow ?? 0) * k);
      P[n * 4] = pos.x; P[n * 4 + 1] = pos.y; P[n * 4 + 2] = pos.z; P[n * 4 + 3] = size;
      const a = (p.alpha ?? 1) * (p.fade ? 1 - k * k : 1);
      C[n * 4] = p.color[0]; C[n * 4 + 1] = p.color[1]; C[n * 4 + 2] = p.color[2]; C[n * 4 + 3] = a;
      let sky = -1, bl = 0;
      if (!p.bright) { const l = engine.lightNear(pos); sky = l[0]; bl = l[1]; }
      M[n * 4] = layer; M[n * 4 + 1] = sky; M[n * 4 + 2] = bl; M[n * 4 + 3] = (p.roll ?? 0) + (p.sway ? age * 0.8 : 0);
      n++;
    }
    this.geometry.instanceCount = n;
    this.iPos.needsUpdate = true; this.iCol.needsUpdate = true; this.iMisc.needsUpdate = true;
    this.mesh.visible = n > 0;
  }
}
