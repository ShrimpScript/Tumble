// Core engine: renderer, terrain, sky, sun shadows and the per-frame world state.
import * as THREE from 'three';
import { loadTerrain, sharedUniforms } from './terrain.js';
import { makeSky } from './sky.js';

export class Engine {
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this.renderer = new THREE.WebGLRenderer({ antialias: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(1);
    this.renderer.setSize(width, height);
    // Minecraft does its lighting maths on raw sRGB values; so does everything here.
    THREE.ColorManagement.enabled = false;
    this.renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
    this.renderer.autoClear = true;
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(70, width / height, 0.05, 1200);
    this.ticks = 0;

    // Sun shadow map.
    const S = 4096;
    this.shadowTarget = new THREE.WebGLRenderTarget(S, S, { depthBuffer: true });
    this.shadowTarget.depthTexture = new THREE.DepthTexture(S, S);
    this.shadowTarget.depthTexture.type = THREE.UnsignedIntType;
    this.shadowCam = new THREE.OrthographicCamera(-60, 60, 60, -60, 1, 600);
    sharedUniforms.shadowMap.value = this.shadowTarget.depthTexture;
    sharedUniforms.shadowTexel.value = 1 / S;
    this.shadowSize = 60;
  }

  async load(sceneBase, assetBase, sceneOrigin) {
    this.sceneOrigin = sceneOrigin;
    this.terrain = await loadTerrain(sceneBase);
    this.scene.add(this.terrain.group);
    // The creeper's crater: replacement chunks, swapped in at the moment of the blast.
    this.postBlast = await loadTerrain(sceneBase, 'terrain_post', this.terrain);
    this.postBlast.group.visible = false;
    this.scene.add(this.postBlast.group);
    const replaced = new Set(this.postBlast.meta.chunks.map((c) => c.cx + ',' + c.cz));
    this.preBlastChunks = this.terrain.group.children.filter((m) => replaced.has(m.userData.chunk));
    this.sky = makeSky(assetBase, sceneOrigin[1]);
    this.scene.add(this.sky.group);
    await this.sky.ready;
    this.terrainDepth = makeTerrainDepthMaterial(this.terrain.tex);
    const lj = await (await fetch(sceneBase + 'light.json')).json();
    const lb = new Uint8Array(await (await fetch(sceneBase + 'light.u8')).arrayBuffer());
    this.lightGrid = { meta: lj, data: lb };
  }

  // Sky and block light (0..15) at a scene-space point, from the baked light grid.
  lightAt(p) {
    const { meta, data } = this.lightGrid;
    const [ox, oy, oz] = meta.origin;
    const [sx, sy, sz] = meta.size;
    const so = meta.sceneOrigin;
    const x = Math.floor(p.x + so[0]) - ox, y = Math.floor(p.y + so[1]) - oy, z = Math.floor(p.z + so[2]) - oz;
    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return [15, 0];
    const v = data[(x * sy + y) * sz + z];
    return [v >> 4, v & 15];
  }

  // Smoothed light: max of the cell and its neighbours, so a body resting on a floor
  // is lit by the air above it rather than by the block it sank into.
  lightNear(p) {
    let best = [0, 0];
    for (const [dx, dy, dz] of [[0, 0, 0], [0, 0.6, 0], [0.5, 0, 0], [-0.5, 0, 0], [0, 0, 0.5], [0, 0, -0.5]]) {
      const l = this.lightAt({ x: p.x + dx, y: p.y + dy, z: p.z + dz });
      best = [Math.max(best[0], l[0]), Math.max(best[1], l[1])];
    }
    return best;
  }

  setBlasted(on) {
    this.postBlast.group.visible = on;
    for (const m of this.preBlastChunks) m.visible = !on;
  }

  setSun(dirArray, opts = {}) {
    sharedUniforms.sunDir.value.set(...dirArray).normalize();
    if (opts.color) sharedUniforms.sunColor.value.set(opts.color);
  }

  renderShadows(focus) {
    if (!sharedUniforms.shadowOn.value) return;
    const sd = sharedUniforms.sunDir.value;
    const s = this.shadowSize;
    const cam = this.shadowCam;
    // Snap to shadow texels so shadows do not shimmer as the camera moves.
    const texel = (2 * s) / this.shadowTarget.width;
    cam.left = -s; cam.right = s; cam.top = s; cam.bottom = -s;
    cam.near = 1; cam.far = 700;
    cam.position.copy(focus).addScaledVector(sd, 300);
    cam.up.set(0, 1, 0);
    if (Math.abs(sd.y) > 0.99) cam.up.set(0, 0, 1);
    cam.lookAt(focus);
    cam.updateMatrixWorld();
    const v = new THREE.Vector3().copy(focus).applyMatrix4(cam.matrixWorldInverse);
    const sx = Math.round(v.x / texel) * texel - v.x, sy = Math.round(v.y / texel) * texel - v.y;
    const right = new THREE.Vector3().setFromMatrixColumn(cam.matrixWorld, 0);
    const up = new THREE.Vector3().setFromMatrixColumn(cam.matrixWorld, 1);
    cam.position.addScaledVector(right, -sx).addScaledVector(up, -sy);
    cam.updateMatrixWorld();
    cam.updateProjectionMatrix();

    const swapped = [];
    this.scene.traverse((o) => {
      if (!o.isMesh && !o.isPoints && !o.isSprite) return;
      const dm = o.userData.depthMaterial || (o.userData.kind === 'solid' ? this.terrainDepth : null);
      if (dm && o.visible) {
        swapped.push([o, o.material, o.visible]);
        o.material = dm;
      } else if (o.visible) {
        swapped.push([o, o.material, true]);
        o.visible = false;
      }
    });
    const r = this.renderer;
    r.setRenderTarget(this.shadowTarget);
    r.setClearColor(0xffffff, 1);
    r.clear();
    r.render(this.scene, cam);
    r.setRenderTarget(null);
    for (const [o, m, vis] of swapped) { o.material = m; o.visible = vis; }
    sharedUniforms.shadowMatrix.value.multiplyMatrices(cam.projectionMatrix, cam.matrixWorldInverse);
  }

  render(opts = {}, target = null) {
    const cam = this.camera;
    cam.updateMatrixWorld();
    sharedUniforms.cameraWorld.value.copy(cam.position);
    for (const m of Object.values(this.terrain.mats)) m.uniforms.ticks.value = this.ticks;
    this.sky.update(cam, opts);
    this.renderShadows(opts.shadowFocus || cam.position);
    this.renderer.setRenderTarget(target);
    this.renderer.setClearColor(0x000000, 1);
    this.renderer.clear();
    this.renderer.render(this.scene, cam);
  }
}

function makeTerrainDepthMaterial(tex) {
  return new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3,
    uniforms: { blocks: { value: tex } },
    vertexShader: `in float layer; out vec2 vUv; flat out float vLayer; void main(){ vUv = uv; vLayer = layer; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
    fragmentShader: `precision highp float; precision highp sampler2DArray; uniform sampler2DArray blocks; in vec2 vUv; flat in float vLayer; out vec4 o;
      void main(){ if (texture(blocks, vec3(vUv, vLayer)).a < 0.5) discard; o = vec4(1.0); }`,
    side: THREE.DoubleSide,
  });
}

export { sharedUniforms };
