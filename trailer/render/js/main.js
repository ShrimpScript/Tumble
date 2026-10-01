// Entry point: builds the world, the cast and the timeline, then renders any frame on
// demand. Every frame is a pure function of its time, so frames can be rendered in any
// order, re-rendered alone, or split across processes.
import * as THREE from 'three';
import { Engine, sharedUniforms } from './engine.js';
import { Humanoid, Creeper, makeItem, loadImageData, entityMaterial, entityDepthMaterial, mcTexture } from './entities.js';
import { Particles } from './particles.js';
import { Post } from './post.js';
import { Overlay } from './overlay.js';
import { loadSim } from './sims.js';
import { buildTimeline } from './shots.js';
import { CamSolver } from './camsolve.js';
import { Rain, makeBolt } from './effects.js';

const params = new URLSearchParams(location.search);
const W = +(params.get('w') || 1920), H = +(params.get('h') || 1080);
export const FPS = +(params.get('fps') || 30);

const engine = new Engine(W, H);
const glCanvas = engine.renderer.domElement;
document.body.appendChild(glCanvas);
const finalCanvas = document.createElement('canvas');
finalCanvas.width = W; finalCanvas.height = H;
const fctx = finalCanvas.getContext('2d');
window.THREE = THREE;
window.engine = engine;
window.U = sharedUniforms;

const post = new Post(engine.renderer, W, H);
const overlay = new Overlay(W, H);
const particles = new Particles('/assets/');

// First-person view model: the held item drawn in its own pass with the game's fixed
// 70 degree hand FOV, exactly like GameRenderer.renderItemInHand.
const fp = { scene: new THREE.Scene(), cam: new THREE.PerspectiveCamera(70, W / H, 0.05, 10), root: new THREE.Group() };
fp.scene.add(fp.root);

const cast = {};
const sims = {};
let timeline = null;

window.boot = async () => {
  const origin = await (await fetch('/scene/light.json')).json();
  const so = origin.sceneOrigin;
  await engine.load('/scene/', '/assets/', so);
  await particles.load();
  engine.scene.add(particles.mesh);
  await overlay.load();

  const item = async (name) => makeItem(`/assets/textures/item/${name}.png`, await loadImageData(`/assets/textures/item/${name}.png`));
  cast.items = {
    pickaxe: await item('iron_pickaxe'),
    sword: await item('iron_sword'),
    pickaxeFP: await item('iron_pickaxe'),
  };
  cast.steve = new Humanoid('/assets/textures/entity/player/wide/steve.png');
  cast.body = new Humanoid('/assets/textures/entity/player/wide/steve.png');   // the corpse
  cast.zombie = new Humanoid('/assets/textures/entity/zombie/zombie.png', 'zombie');
  cast.creeper = new Creeper('/assets/');
  for (const k of ['steve', 'body', 'zombie', 'creeper']) {
    engine.scene.add(cast[k].group);
    cast[k].group.visible = false;
  }

  // Elytra: ElytraModel's two wings, in model space like ElytraLayer.
  cast.elytra = makeElytra();
  cast.steve.lift.add(cast.elytra.holder);
  cast.elytra.holder.visible = false;
  cast.rain = new Rain('/assets/');
  engine.scene.add(cast.rain.group);
  cast.rain.group.visible = false;
  cast.makeBolt = makeBolt;

  // First-person pickaxe hierarchy (ItemInHandRenderer, right hand, handheld).
  const fpItem = new THREE.Mesh(cast.items.pickaxeFP.geometry, cast.items.pickaxeFP.mat);
  fpItem.position.set(-0.5, -0.5, -0.5);
  fp.attack = new THREE.Group();
  fp.display = new THREE.Group();
  fp.display.position.set(1.13 / 16, 3.2 / 16, 1.13 / 16);
  fp.display.rotation.order = 'XYZ';
  fp.display.rotation.set(0, -Math.PI / 2, 25 * Math.PI / 180);
  fp.display.scale.setScalar(0.68);
  fp.display.add(fpItem);
  fp.attack.add(fp.display);
  fp.root.add(fp.attack);

  // GUI item icons used by the HUD, the corpse screen and the hotbar.
  for (const n of ['item/iron_pickaxe', 'item/iron_sword', 'item/bread', 'item/cooked_beef', 'item/apple', 'item/coal', 'item/raw_iron', 'block/torch', 'item/stick', 'item/wheat_seeds']) await overlay.item(n);
  await overlay.item('cherry_log_top|cherry_log|cherry_log', 'block');
  await overlay.item('cobblestone|cobblestone|cobblestone', 'block');
  await overlay.item('dirt|dirt|dirt', 'block');
  await overlay.item('crafting_table_top|crafting_table_front|crafting_table_side', 'block');
  await overlay.item('cherry_planks|cherry_planks|cherry_planks', 'block');

  for (const name of (await (await fetch('/sim/index.json')).json())) sims[name] = await loadSim('/sim/', name, so);
  const hm = await (await fetch('/scene/heights.json')).json();
  const heights = { meta: hm, data: new Int16Array(await (await fetch('/scene/heights.i16')).arrayBuffer()) };
  const fallMeta = await (await fetch('/sim/fall.meta.json')).json();
  const montageMeta = await (await fetch('/sim/montage.meta.json')).json();
  const cm = await (await fetch('/scene/collision.json')).json();
  const solver = new CamSolver(new Uint8Array(await (await fetch('/scene/occ.u8')).arrayBuffer()), cm, so);
  window.solver = solver;

  timeline = buildTimeline({ engine, cast, sims, particles, overlay, fp, post, so, W, H, FPS, heights, fallMeta, montageMeta, solver });
  window.booted = true;
  return { duration: timeline.duration, frames: Math.round(timeline.duration * FPS) };
};

const lerp = (a, b, t) => a + (b - a) * t;

function makeElytra() {
  const map = mcTexture('/assets/textures/entity/elytra.png');
  const mat = entityMaterial(map, { side: THREE.DoubleSide });
  const depth = entityDepthMaterial(map);
  const wing = (mirror) => {
    // ElytraModel: texOffs(22, 0).addBox(-10, 0, 0, ...) left, mirror().addBox(0, 0, 0, ...) right, deformation 1.0
    const g = new THREE.BoxGeometry(1, 1, 1);
    const pos = [], uv = [], nrm = [];
    const x = mirror ? 0 : -10, y = 0, z = 0, w = 10, h = 20, d = 2, grow = 1;
    let x0 = x - grow, x1 = x + w + grow; const y0 = y - grow, y1 = y + h + grow, z0 = z - grow, z1 = z + d + grow;
    if (mirror) [x0, x1] = [x1, x0];
    const V = [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]];
    const u = 22, v = 0;
    const f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d, f9 = u + d + w + d + w, f10 = v, f11 = v + d, f12 = v + d + h;
    const polys = [[[5, 1, 2, 6], f6, f11, f8, f12, [1, 0, 0]], [[0, 4, 7, 3], f4, f11, f5, f12, [-1, 0, 0]], [[5, 4, 0, 1], f5, f10, f6, f11, [0, -1, 0]],
      [[2, 3, 7, 6], f6, f11, f7, f10, [0, 1, 0]], [[1, 0, 3, 2], f5, f11, f6, f12, [0, 0, -1]], [[4, 5, 6, 7], f8, f11, f9, f12, [0, 0, 1]]];
    for (const [vi, u1, v1, u2, v2, n] of polys) {
      let idx = vi.slice(), uvs = [[u2, v1], [u1, v1], [u1, v2], [u2, v2]];
      if (mirror) { idx = idx.reverse(); uvs = uvs.reverse(); }
      for (const k of [0, 1, 2, 0, 2, 3]) { const p = V[idx[k]]; pos.push(p[0] / 16, p[1] / 16, p[2] / 16); uv.push(uvs[k][0] / 64, uvs[k][1] / 32); nrm.push(mirror ? -n[0] : n[0], n[1], n[2]); }
    }
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
    g.setIndex(null);
    g.clearGroups();
    const m = new THREE.Mesh(g, mat);
    m.userData.depthMaterial = depth;
    m.frustumCulled = false;
    const pivot = new THREE.Group();
    pivot.rotation.order = 'ZYX';
    pivot.add(m);
    return pivot;
  };
  const left = wing(false), right = wing(true);
  left.position.set(5 / 16, 0, 0);
  right.position.set(-5 / 16, 0, 0);
  // ElytraLayer: translate(0, 0, 0.125) in model space.
  const holder = new THREE.Group();
  holder.position.set(0, 0, 2 / 16);
  holder.add(left, right);
  // ElytraModel.setupAnim: 15 degree fold at rest, spread to 20 / 90 degrees when gliding.
  const spread = (k) => {
    const xr = lerp(0.2617994, 0.34906584, k), zr = lerp(-0.2617994, -Math.PI / 2, k);
    left.rotation.set(xr, 0, zr);
    right.rotation.set(xr, 0, -zr);
  };
  spread(0);
  return { holder, left, right, mat, spread, setLight: (s, b) => mat.uniforms.lightLevel.value.set(s, b) };
}

// Renders one frame and returns it as a PNG blob.
async function frameBlob(i) {
  const t = i / FPS;
  const S = timeline.frameState(t);
  engine.ticks = S.ticks ?? t * 20;
  particles.update(S.particleTime ?? t, engine);
  post.set(S.post || {});
  engine.render(S.render || {}, post.target);
  if (S.fp) {
    engine.renderer.autoClear = false;
    engine.renderer.setRenderTarget(post.target);
    engine.renderer.clearDepth();
    fp.cam.aspect = W / H;
    fp.cam.updateProjectionMatrix();
    engine.renderer.render(fp.scene, fp.cam);
    engine.renderer.autoClear = true;
  }
  post.finish(engine.camera);
  fctx.clearRect(0, 0, W, H);
  fctx.drawImage(glCanvas, 0, 0);
  if (S.draw) S.draw(fctx, overlay);
  return new Promise((res) => finalCanvas.toBlob(res, 'image/png'));
}

window.renderFrame = async (i, postUrl) => {
  const t0 = performance.now();
  const blob = await frameBlob(i);
  if (postUrl) {
    await fetch(postUrl, { method: 'POST', body: blob });
  }
  return performance.now() - t0;
};

window.cues = () => timeline.cues;

// Reports frames whose camera sits inside, or within near-plane reach of, solid terrain.
window.audit = (from, to, step = 3) => {
  const bad = [];
  for (let i = Math.round(from * FPS); i < Math.round(to * FPS); i += step) {
    const S = timeline.frameState(i / FPS);
    if (S.post?.fade >= 1) continue;
    const p = engine.camera.position;
    const v = window.solver.at(p);
    if (v === 1 || !window.solver.clear(p, 0.12)) bad.push([i, (i / FPS).toFixed(2), p.toArray().map((x) => +x.toFixed(2)), v]);
  }
  return bad;
};
window.shotList = () => timeline.shots.map((s) => ({ name: s.name, start: s.start, end: s.end }));

// Scouting helper kept for location work: one still from a camera position.
window.scout = (o) => {
  const c = engine.camera;
  c.fov = o.fov || 70;
  c.aspect = W / H;
  c.updateProjectionMatrix();
  c.position.set(...o.pos);
  c.lookAt(new THREE.Vector3(...o.look));
  sharedUniforms.shadowOn.value = o.shadows === false ? 0 : 1;
  sharedUniforms.gammaSetting.value = o.gamma ?? 0.5;
  if (o.sun) engine.setSun(o.sun);
  if (o.fog) { sharedUniforms.fogStart.value = o.fog[0]; sharedUniforms.fogEnd.value = o.fog[1]; }
  post.set({});
  engine.render({ shadowFocus: new THREE.Vector3(...(o.focus || o.look)) }, post.target);
  post.finish(c);
  return 0;
};
