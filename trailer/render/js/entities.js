// Entity models built the way vanilla builds them: ModelPart cubes with box UVs,
// HumanoidModel / CreeperModel pivots and animation formulas, and the same
// rotateY(180 - yaw) / scale(-1, -1, 1) / lift transform the renderers use.
import * as THREE from 'three';
import { LIGHTMAP_GLSL, SHADOW_GLSL, FOG_GLSL } from './shaderlib.js';
import { sharedUniforms } from './terrain.js';

const loader = new THREE.TextureLoader();
const texCache = {};
export function mcTexture(url) {
  if (!texCache[url]) {
    const t = loader.load(url);
    t.magFilter = THREE.NearestFilter;
    t.minFilter = THREE.NearestFilter;
    t.generateMipmaps = false;
    t.colorSpace = THREE.NoColorSpace;
    t.flipY = false;
    texCache[url] = t;
  }
  return texCache[url];
}

// ModelPart.Cube: vertices and box UVs, in model pixels (Y down), relative to the pivot.
function cube(out, texW, texH, u, v, x, y, z, w, h, d, grow = 0, mirror = false) {
  let x0 = x - grow, y0 = y - grow, z0 = z - grow;
  let x1 = x + w + grow, y1 = y + h + grow, z1 = z + d + grow;
  if (mirror) [x0, x1] = [x1, x0];
  const V = [
    [x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0],
    [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1],
  ];
  const f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d, f9 = u + d + w + d + w;
  const f10 = v, f11 = v + d, f12 = v + d + h;
  // [vertex indices], u1, v1, u2, v2, normal (model space)
  const polys = [
    [[5, 1, 2, 6], f6, f11, f8, f12, [1, 0, 0]],   // east
    [[0, 4, 7, 3], f4, f11, f5, f12, [-1, 0, 0]],  // west
    [[5, 4, 0, 1], f5, f10, f6, f11, [0, -1, 0]],  // down (model) = top in world
    [[2, 3, 7, 6], f6, f11, f7, f10, [0, 1, 0]],   // up (model) = bottom in world
    [[1, 0, 3, 2], f5, f11, f6, f12, [0, 0, -1]],  // north
    [[4, 5, 6, 7], f8, f11, f9, f12, [0, 0, 1]],   // south
  ];
  for (const [vi, u1, v1, u2, v2, n] of polys) {
    let idx = vi.slice();
    let uv = [[u2, v1], [u1, v1], [u1, v2], [u2, v2]];
    let nn = n.slice();
    if (mirror) {
      idx = idx.reverse();
      uv = uv.reverse();
      // Mirroring swaps the X faces; the polygon list order handles that in vanilla.
      nn = [-n[0], n[1], n[2]];
    }
    // Two triangles (0,1,2) (0,2,3). Vanilla polygons wind clockwise in model space,
    // which becomes counter-clockwise after the (-1,-1,1) flip.
    for (const k of [0, 1, 2, 0, 2, 3]) {
      const p = V[idx[k]];
      out.pos.push(p[0] / 16, p[1] / 16, p[2] / 16);
      out.uv.push(uv[k][0] / texW, uv[k][1] / texH);
      out.nrm.push(...nn);
    }
  }
}

function geom(build) {
  const out = { pos: [], uv: [], nrm: [] };
  build(out);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(out.pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(out.uv, 2));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(out.nrm, 3));
  g.computeBoundingSphere();
  return g;
}

// Entity lighting: Lighting.setupLevel's two diffuse lights, 0.4 ambient + 0.6 diffuse.
const L0 = new THREE.Vector3(0.2, 1.0, -0.7).normalize();
const L1 = new THREE.Vector3(-0.2, 1.0, 0.7).normalize();

export function entityMaterial(map, opts = {}) {
  const m = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3,
    uniforms: Object.assign({
      map: { value: map },
      lightLevel: { value: new THREE.Vector2(15, 0) },
      hurt: { value: 0 },
      whiteFlash: { value: 0 },
      L0: { value: L0 }, L1: { value: L1 },
      opacity: { value: 1 },
      tintColor: { value: new THREE.Color(1, 1, 1) },
    }, sharedUniforms),
    vertexShader: /* glsl */`
      out vec2 vUv; out vec3 vN; out vec3 vWorld; out float vDepth;
      void main() {
        vUv = uv;
        vN = normalize(mat3(modelMatrix) * normal);
        vec4 w = modelMatrix * vec4(position, 1.0);
        vWorld = w.xyz;
        vec4 mv = viewMatrix * w;
        vDepth = -mv.z;
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: /* glsl */`
      precision highp float;
      uniform sampler2D map; uniform vec2 lightLevel; uniform float hurt; uniform float whiteFlash;
      uniform vec3 L0; uniform vec3 L1; uniform float opacity; uniform vec3 tintColor;
      in vec2 vUv; in vec3 vN; in vec3 vWorld; in float vDepth; out vec4 outColor;
      ${LIGHTMAP_GLSL}
      ${SHADOW_GLSL}
      ${FOG_GLSL}
      void main() {
        vec4 t = texture(map, vUv);
        if (t.a < 0.1) discard;
        vec3 n = normalize(vN);
        if (!gl_FrontFacing) n = -n;
        float diffuse = min(1.0, 0.4 + 0.6 * (max(dot(n, L0), 0.0) + max(dot(n, L1), 0.0)));
        float sun = sunShadow(vWorld, n, lightLevel.x);
        vec3 lm = lightmap(lightLevel.x, lightLevel.y, sun);
        vec3 c = t.rgb * tintColor;
        c = mix(c, vec3(1.0), whiteFlash);
        c *= lm * diffuse;
        c = mix(c, vec3(1.0, 0.0, 0.0) * lm, hurt * 0.3);
        c = applyFog(c, vWorld, vDepth);
        outColor = vec4(c, opacity);
      }`,
    side: opts.side ?? THREE.FrontSide,
    transparent: !!opts.transparent,
  });
  m.userData.isEntity = true;
  return m;
}

export function entityDepthMaterial(map) {
  return new THREE.ShaderMaterial({
    uniforms: { map: { value: map } },
    vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
    fragmentShader: 'uniform sampler2D map; varying vec2 vUv; void main(){ if (texture2D(map, vUv).a < 0.1) discard; gl_FragColor = vec4(1.0); }',
    side: THREE.DoubleSide,
  });
}

// -- Humanoids ---------------------------------------------------------------------------

// Part definitions from PlayerModel.createMesh (wide arms) and HumanoidModel.createMesh.
const PLAYER_PARTS = {
  head: { pivot: [0, 0, 0], cubes: [[0, 0, -4, -8, -4, 8, 8, 8, 0], [32, 0, -4, -8, -4, 8, 8, 8, 0.5]] },
  body: { pivot: [0, 0, 0], cubes: [[16, 16, -4, 0, -2, 8, 12, 4, 0], [16, 32, -4, 0, -2, 8, 12, 4, 0.25]] },
  rightArm: { pivot: [-5, 2, 0], cubes: [[40, 16, -3, -2, -2, 4, 12, 4, 0], [40, 32, -3, -2, -2, 4, 12, 4, 0.25]] },
  leftArm: { pivot: [5, 2, 0], cubes: [[32, 48, -1, -2, -2, 4, 12, 4, 0], [48, 48, -1, -2, -2, 4, 12, 4, 0.25]] },
  rightLeg: { pivot: [-1.9, 12, 0], cubes: [[0, 16, -2, 0, -2, 4, 12, 4, 0], [0, 32, -2, 0, -2, 4, 12, 4, 0.25]] },
  leftLeg: { pivot: [1.9, 12, 0], cubes: [[16, 48, -2, 0, -2, 4, 12, 4, 0], [0, 48, -2, 0, -2, 4, 12, 4, 0.25]] },
};
const ZOMBIE_PARTS = {
  head: { pivot: [0, 0, 0], cubes: [[0, 0, -4, -8, -4, 8, 8, 8, 0], [32, 0, -4, -8, -4, 8, 8, 8, 0.5]] },
  body: { pivot: [0, 0, 0], cubes: [[16, 16, -4, 0, -2, 8, 12, 4, 0]] },
  rightArm: { pivot: [-5, 2, 0], cubes: [[40, 16, -3, -2, -2, 4, 12, 4, 0]] },
  leftArm: { pivot: [5, 2, 0], cubes: [[40, 16, -1, -2, -2, 4, 12, 4, 0, true]] },
  rightLeg: { pivot: [-1.9, 12, 0], cubes: [[0, 16, -2, 0, -2, 4, 12, 4, 0]] },
  leftLeg: { pivot: [1.9, 12, 0], cubes: [[0, 16, -2, 0, -2, 4, 12, 4, 0, true]] },
};
// Ragdoll body order (BodyPart enum) and cuboid centres from RagdollEntityRenderer.
export const RAGDOLL_ORDER = ['head', 'body', 'leftArm', 'rightArm', 'leftLeg', 'rightLeg'];
const CUBOID_CENTRE = { head: [0, -4, 0], body: [0, 6, 0], leftArm: [1, 4, 0], rightArm: [-1, 4, 0], leftLeg: [0, 6, 0], rightLeg: [0, 6, 0] };

export class Humanoid {
  constructor(skinUrl, kind = 'player', texH = 64) {
    this.kind = kind;
    const defs = kind === 'zombie' ? ZOMBIE_PARTS : PLAYER_PARTS;
    this.map = mcTexture(skinUrl);
    this.mat = entityMaterial(this.map, { side: THREE.DoubleSide });
    this.depthMat = entityDepthMaterial(this.map);
    this.root = new THREE.Group();          // translate(pos) * rotY(180 - yaw)
    const flip = new THREE.Group();         // scale(-1, -1, 1)
    flip.scale.set(-1, -1, 1);
    const lift = new THREE.Group();         // translate(0, -1.501, 0)
    lift.position.set(0, -1.501, 0);
    this.root.add(flip);
    flip.add(lift);
    this.lift = lift;
    this.parts = {};
    this.ragParts = {};
    this.ragRoot = new THREE.Group();
    for (const [name, def] of Object.entries(defs)) {
      const g = geom((out) => {
        for (const c of def.cubes) cube(out, 64, texH, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]);
      });
      const part = new THREE.Group();
      part.position.set(def.pivot[0] / 16, def.pivot[1] / 16, def.pivot[2] / 16);
      part.rotation.order = 'ZYX';
      const mesh = new THREE.Mesh(g, this.mat);
      mesh.userData.depthMaterial = this.depthMat;
      mesh.frustumCulled = false;
      part.add(mesh);
      lift.add(part);
      this.parts[name] = part;
      // Ragdoll twin: translate(pos) * rot(q) * scale(-1,-1,1) * translate(-centre/16).
      const rp = new THREE.Group();
      const rflip = new THREE.Group();
      rflip.scale.set(-1, -1, 1);
      const rc = new THREE.Group();
      const cc = CUBOID_CENTRE[name];
      rc.position.set(-cc[0] / 16, -cc[1] / 16, -cc[2] / 16);
      const rmesh = new THREE.Mesh(g, this.mat);
      rmesh.userData.depthMaterial = this.depthMat;
      rmesh.frustumCulled = false;
      rc.add(rmesh);
      rflip.add(rc);
      rp.add(rflip);
      this.ragRoot.add(rp);
      this.ragParts[name] = { group: rp, centre: rc };
    }
    this.group = new THREE.Group();
    this.group.add(this.root);
    this.group.add(this.ragRoot);
    this.held = null;
    this.ragdoll = false;
  }

  setVisible(v) { this.group.visible = v; }

  // Pose from vanilla HumanoidModel.setupAnim terms. All angles radians unless noted.
  pose(p) {
    this.ragdoll = false;
    this.root.visible = true;
    this.ragRoot.visible = false;
    this.root.position.set(p.x, p.y, p.z);
    // PlayerRenderer.setupRotations: rotY(180 - yaw), then for elytra flight a pitch of
    // (-90 - xRot) about the feet so the body lies along the flight path.
    this.root.rotation.order = 'YXZ';
    this.root.rotation.set((p.rootPitch || 0) * Math.PI / 180, Math.PI - p.bodyYaw * Math.PI / 180, 0);
    const P = this.parts;
    for (const k in P) P[k].rotation.set(0, 0, 0);
    const ls = p.limbSwing || 0, la = p.limbAmount || 0, age = p.age || 0;
    P.head.rotation.y = (p.headYaw || 0) * Math.PI / 180;
    P.head.rotation.x = (p.headPitch || 0) * Math.PI / 180;
    P.rightArm.rotation.x = Math.cos(ls * 0.6662 + Math.PI) * 2.0 * la * 0.5;
    P.leftArm.rotation.x = Math.cos(ls * 0.6662) * 2.0 * la * 0.5;
    P.rightLeg.rotation.x = Math.cos(ls * 0.6662) * 1.4 * la;
    P.leftLeg.rotation.x = Math.cos(ls * 0.6662 + Math.PI) * 1.4 * la;
    if (this.kind === 'zombie' && !p.noZombieArms) {
      // AnimationUtils.animateZombieArms
      const f = Math.sin((p.attack || 0) * Math.PI);
      const f1 = Math.sin((1 - (1 - (p.attack || 0)) * (1 - (p.attack || 0))) * Math.PI);
      P.rightArm.rotation.z = 0; P.leftArm.rotation.z = 0;
      P.rightArm.rotation.y = -(0.1 - f * 0.6); P.leftArm.rotation.y = 0.1 - f * 0.6;
      const f2 = -Math.PI / 2.25;
      P.rightArm.rotation.x = f2 + f * 1.2 - f1 * 0.4;
      P.leftArm.rotation.x = f2 + f * 1.2 - f1 * 0.4;
    }
    if (p.heldPose === 'item') P.rightArm.rotation.x = P.rightArm.rotation.x * 0.5 - Math.PI / 10;
    if (p.flying) {
      // HumanoidModel.setupAnim while fall flying: head forward, limbs trailing.
      P.head.rotation.x = -Math.PI / 4;
      P.rightArm.rotation.x = 0.15; P.leftArm.rotation.x = 0.15;
      P.rightLeg.rotation.x = 0.05; P.leftLeg.rotation.x = -0.05;
    }
    // AnimationUtils.bobArms
    P.rightArm.rotation.z += Math.cos(age * 0.09) * 0.05 + 0.05;
    P.leftArm.rotation.z -= Math.cos(age * 0.09) * 0.05 + 0.05;
    P.rightArm.rotation.x += Math.sin(age * 0.067) * 0.05;
    P.leftArm.rotation.x -= Math.sin(age * 0.067) * 0.05;
    if (p.attack > 0) this._attack(p.attack, P);
    if (p.crouch) {
      P.body.rotation.x = 0.5;
      P.rightArm.rotation.x += 0.4; P.leftArm.rotation.x += 0.4;
      P.rightLeg.position.z = 4 / 16; P.leftLeg.position.z = 4 / 16;
      P.rightLeg.position.y = 12.2 / 16; P.leftLeg.position.y = 12.2 / 16;
      P.head.position.y = 4.2 / 16; P.body.position.y = 3.2 / 16;
      P.leftArm.position.y = 5.2 / 16; P.rightArm.position.y = 5.2 / 16;
    } else {
      P.rightLeg.position.set(-1.9 / 16, 12 / 16, 0); P.leftLeg.position.set(1.9 / 16, 12 / 16, 0);
      P.head.position.set(0, 0, 0); P.body.position.set(0, 0, 0);
      P.rightArm.position.set(-5 / 16, 2 / 16, 0); P.leftArm.position.set(5 / 16, 2 / 16, 0);
    }
    if (p.armsUp) { P.rightArm.rotation.x = -2.6 + p.armsUp; P.leftArm.rotation.x = -2.6 + p.armsUp; P.rightArm.rotation.z = 0.3; P.leftArm.rotation.z = -0.3; }
    if (p.override) for (const [k, r] of Object.entries(p.override)) P[k].rotation.set(r[0], r[1], r[2]);
    this.hurt(p.hurt || 0);
  }

  // HumanoidModel.setupAttackAnimation for the right arm.
  _attack(t, P) {
    const bodyY = Math.sin(Math.sqrt(t) * Math.PI * 2) * 0.2;
    P.body.rotation.y = bodyY;
    P.rightArm.position.z = Math.sin(bodyY) * 5 / 16;
    P.rightArm.position.x = -Math.cos(bodyY) * 5 / 16;
    P.leftArm.position.z = -Math.sin(bodyY) * 5 / 16;
    P.leftArm.position.x = Math.cos(bodyY) * 5 / 16;
    P.rightArm.rotation.y += bodyY;
    P.leftArm.rotation.y += bodyY;
    P.leftArm.rotation.x += bodyY;
    let f = 1 - t; f = f * f * f * f; f = 1 - f;
    const f1 = Math.sin(f * Math.PI);
    const f2 = Math.sin(t * Math.PI) * -(P.head.rotation.x - 0.7) * 0.75;
    P.rightArm.rotation.x -= f1 * 1.2 + f2;
    P.rightArm.rotation.y += bodyY * 2;
    P.rightArm.rotation.z += Math.sin(t * Math.PI) * -0.4;
  }

  // Ragdoll: six bodies straight from the solver (scene coordinates).
  setRagdoll(bodies) {
    this.ragdoll = true;
    this.root.visible = false;
    this.ragRoot.visible = true;
    for (let i = 0; i < 6; i++) {
      const name = RAGDOLL_ORDER[i];
      const b = bodies[i];
      const g = this.ragParts[name].group;
      g.position.set(b[0], b[1], b[2]);
      g.quaternion.set(b[3], b[4], b[5], b[6]);
    }
  }

  setLight(sky, block) { this.mat.uniforms.lightLevel.value.set(sky, block); if (this.held) this.held.mat.uniforms.lightLevel.value.set(sky, block); }
  hurt(v) { this.mat.uniforms.hurt.value = v; }

  // Held item in the right hand, placed like ItemInHandLayer + handheld display transform.
  hold(item) {
    if (this.held === item) return;
    if (this.held) { this.held.pose.parent.remove(this.held.pose); this.held.rag.parent.remove(this.held.rag); }
    this.held = item;
    if (!item) return;
    const mk = (parent) => {
      const g = new THREE.Group();
      // ItemInHandLayer: rotX(-90), rotY(180), translate(1/16, 2/16, -10/16)
      g.rotation.order = 'XYZ';
      const a = new THREE.Group(); a.rotation.x = -Math.PI / 2;
      const b = new THREE.Group(); b.rotation.y = Math.PI;
      const c = new THREE.Group(); c.position.set(1 / 16, 0.125, -0.625);
      // handheld thirdperson_righthand: translation [0,4,0.5]/16, rotation [0,-90,55], scale 0.85
      const d = new THREE.Group(); d.position.set(0, 4 / 16, 0.5 / 16);
      const e = new THREE.Group(); e.rotation.order = 'XYZ'; e.rotation.set(0, -Math.PI / 2, 55 * Math.PI / 180);
      const f = new THREE.Group(); f.scale.setScalar(0.85);
      const m = new THREE.Mesh(item.geometry, item.mat);
      m.userData.depthMaterial = item.depthMat;
      m.frustumCulled = false;
      m.position.set(-0.5, -0.5, -0.5);
      f.add(m); e.add(f); d.add(e); c.add(d); b.add(c); a.add(b); g.add(a);
      parent.add(g);
      return g;
    };
    item.pose = mk(this.parts.rightArm);
    // Ragdoll: the stack is at the arm pivot in model space after translate(-centre).
    item.rag = mk(this.ragParts.rightArm.centre);
  }
}

// -- Creeper ------------------------------------------------------------------------------

export class Creeper {
  constructor(assetBase) {
    this.map = mcTexture(assetBase + 'textures/entity/creeper/creeper.png');
    this.mat = entityMaterial(this.map);
    this.depthMat = entityDepthMaterial(this.map);
    this.root = new THREE.Group();
    this.swell = new THREE.Group();
    const flip = new THREE.Group(); flip.scale.set(-1, -1, 1);
    const lift = new THREE.Group(); lift.position.set(0, -1.501, 0);
    this.root.add(this.swell); this.swell.add(flip); flip.add(lift);
    const defs = {
      head: { pivot: [0, 6, 0], c: [0, 0, -4, -8, -4, 8, 8, 8] },
      body: { pivot: [0, 6, 0], c: [16, 16, -4, 0, -2, 8, 12, 4] },
      rightHind: { pivot: [-2, 18, 4], c: [0, 16, -2, 0, -2, 4, 6, 4] },
      leftHind: { pivot: [2, 18, 4], c: [0, 16, -2, 0, -2, 4, 6, 4] },
      rightFront: { pivot: [-2, 18, -4], c: [0, 16, -2, 0, -2, 4, 6, 4] },
      leftFront: { pivot: [2, 18, -4], c: [0, 16, -2, 0, -2, 4, 6, 4] },
    };
    this.parts = {};
    for (const [k, d] of Object.entries(defs)) {
      const g = geom((out) => cube(out, 64, 32, ...d.c));
      const part = new THREE.Group();
      part.position.set(d.pivot[0] / 16, d.pivot[1] / 16, d.pivot[2] / 16);
      part.rotation.order = 'ZYX';
      const m = new THREE.Mesh(g, this.mat);
      m.userData.depthMaterial = this.depthMat;
      m.frustumCulled = false;
      part.add(m);
      lift.add(part);
      this.parts[k] = part;
    }
    this.group = this.root;
  }

  pose(p) {
    this.root.position.set(p.x, p.y, p.z);
    this.root.rotation.set(0, Math.PI - p.bodyYaw * Math.PI / 180, 0);
    const P = this.parts, ls = p.limbSwing || 0, la = p.limbAmount || 0;
    P.head.rotation.set((p.headPitch || 0) * Math.PI / 180, (p.headYaw || 0) * Math.PI / 180, 0);
    P.rightHind.rotation.x = Math.cos(ls * 0.6662) * 1.4 * la;
    P.leftHind.rotation.x = Math.cos(ls * 0.6662 + Math.PI) * 1.4 * la;
    P.rightFront.rotation.x = Math.cos(ls * 0.6662 + Math.PI) * 1.4 * la;
    P.leftFront.rotation.x = Math.cos(ls * 0.6662) * 1.4 * la;
    // CreeperRenderer.scale and getWhiteOverlayProgress.
    let f = p.swell || 0;
    const f1 = 1 + Math.sin(f * 100) * f * 0.01;
    f = Math.min(Math.max(f, 0), 1); f *= f; f *= f;
    const f2 = (1 + f * 0.4) * f1, f3 = (1 + f * 0.1) / f1;
    this.swell.scale.set(f2, f3, f2);
    const s = p.swell || 0;
    this.mat.uniforms.whiteFlash.value = Math.floor(s * 10) % 2 === 0 ? 0 : Math.min(Math.max(s, 0.5), 1) * 0.9;
  }

  setLight(sky, block) { this.mat.uniforms.lightLevel.value.set(sky, block); }
}

// -- Items --------------------------------------------------------------------------------

// ItemModelGenerator: the sprite on two faces plus an edge strip for every pixel boundary.
export function makeItem(url, imgData) {
  const map = mcTexture(url);
  const out = { pos: [], uv: [], nrm: [] };
  const W = 16, z0 = 7.5 / 16, z1 = 8.5 / 16;
  const quad = (a, b, c, d, uva, uvb, uvc, uvd, n) => {
    for (const [p, t] of [[a, uva], [b, uvb], [c, uvc], [a, uva], [c, uvc], [d, uvd]]) {
      out.pos.push(...p); out.uv.push(...t); out.nrm.push(...n);
    }
  };
  quad([0, 0, z1], [1, 0, z1], [1, 1, z1], [0, 1, z1], [0, 1], [1, 1], [1, 0], [0, 0], [0, 0, 1]);
  quad([1, 0, z0], [0, 0, z0], [0, 1, z0], [1, 1, z0], [1, 1], [0, 1], [0, 0], [1, 0], [0, 0, -1]);
  const op = (x, y) => x >= 0 && y >= 0 && x < W && y < W && imgData[(y * W + x) * 4 + 3] > 0;
  for (let y = 0; y < W; y++) {
    for (let x = 0; x < W; x++) {
      if (!op(x, y)) continue;
      const u = (x + 0.5) / W, v = (y + 0.5) / W;
      const X0 = x / W, X1 = (x + 1) / W, Y1 = 1 - y / W, Y0 = 1 - (y + 1) / W;
      const t = [u, v];
      if (!op(x, y - 1)) quad([X0, Y1, z1], [X1, Y1, z1], [X1, Y1, z0], [X0, Y1, z0], t, t, t, t, [0, 1, 0]);
      if (!op(x, y + 1)) quad([X0, Y0, z0], [X1, Y0, z0], [X1, Y0, z1], [X0, Y0, z1], t, t, t, t, [0, -1, 0]);
      if (!op(x - 1, y)) quad([X0, Y0, z0], [X0, Y0, z1], [X0, Y1, z1], [X0, Y1, z0], t, t, t, t, [-1, 0, 0]);
      if (!op(x + 1, y)) quad([X1, Y0, z1], [X1, Y0, z0], [X1, Y1, z0], [X1, Y1, z1], t, t, t, t, [1, 0, 0]);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(out.pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(out.uv, 2));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(out.nrm, 3));
  const mat = entityMaterial(map, { side: THREE.DoubleSide });
  return { geometry: g, mat, depthMat: entityDepthMaterial(map) };
}

export async function loadImageData(url) {
  const img = new Image();
  img.src = url;
  await img.decode();
  const c = document.createElement('canvas');
  c.width = img.width; c.height = img.height;
  const ctx = c.getContext('2d');
  ctx.drawImage(img, 0, 0);
  return ctx.getImageData(0, 0, img.width, img.height).data;
}
