// Vanilla-style sky: gradient dome, the game's own sun sprite, and fancy 3D clouds
// built from textures/environment/clouds.png at the 1.18+ cloud height of 192.
import * as THREE from 'three';
import { sharedUniforms } from './terrain.js';

export function makeSky(assetBase, sceneOriginY) {
  const group = new THREE.Group();
  const dome = new THREE.Mesh(
    new THREE.SphereGeometry(900, 32, 16),
    new THREE.ShaderMaterial({
      glslVersion: THREE.GLSL3,
      uniforms: {
        skyTop: { value: new THREE.Color(0.47, 0.65, 1.0) },
        horizon: { value: sharedUniforms.fogColor.value },
        sunDir: sharedUniforms.sunDir,
        sunsetColor: { value: new THREE.Color(1.0, 0.55, 0.25) },
        sunset: { value: 0.0 },
        dim: { value: 1.0 },
      },
      vertexShader: `out vec3 vDir; void main(){ vDir = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); gl_Position.z = gl_Position.w; }`,
      fragmentShader: `
        precision highp float;
        in vec3 vDir; out vec4 o;
        uniform vec3 skyTop; uniform vec3 horizon; uniform vec3 sunDir; uniform vec3 sunsetColor; uniform float sunset; uniform float dim;
        void main(){
          float h = vDir.y;
          vec3 c = mix(horizon, skyTop, smoothstep(-0.05, 0.45, h));
          float sd = max(dot(normalize(vec3(vDir.x,0.0,vDir.z)), normalize(vec3(sunDir.x,0.0,sunDir.z))), 0.0);
          c = mix(c, sunsetColor, sunset * pow(sd, 3.0) * (1.0 - smoothstep(0.0, 0.35, abs(h - 0.05))));
          o = vec4(c * dim, 1.0);
        }`,
      side: THREE.BackSide,
      depthWrite: false,
      depthTest: false,
    }));
  dome.renderOrder = -100;
  dome.frustumCulled = false;
  group.add(dome);

  const loader = new THREE.TextureLoader();
  const sunTex = loader.load(assetBase + 'textures/environment/sun.png');
  sunTex.magFilter = THREE.NearestFilter;
  sunTex.colorSpace = THREE.NoColorSpace;
  const sun = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({
    map: sunTex, blending: THREE.AdditiveBlending, depthWrite: false, depthTest: false, transparent: true, fog: false,
  }));
  sun.renderOrder = -99;
  sun.frustumCulled = false;
  group.add(sun);

  const clouds = new THREE.Group();
  group.add(clouds);
  const cloudState = { mesh: null, data: null, w: 0, h: 0 };
  const img = new Image();
  img.src = assetBase + 'textures/environment/clouds.png';
  const ready = new Promise((res) => {
    img.onload = () => {
      const c = document.createElement('canvas');
      c.width = img.width; c.height = img.height;
      const ctx = c.getContext('2d');
      ctx.drawImage(img, 0, 0);
      cloudState.data = ctx.getImageData(0, 0, img.width, img.height).data;
      cloudState.w = img.width; cloudState.h = img.height;
      res();
    };
  });

  const cloudMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3,
    uniforms: { dim: { value: 1.0 }, fogColor: sharedUniforms.fogColor, cam: sharedUniforms.cameraWorld, tint: { value: new THREE.Color(1, 1, 1) }, fogEnd: sharedUniforms.fogEnd },
    vertexShader: `in float shade; out float vShade; out vec3 vW; void main(){ vShade = shade; vec4 w = modelMatrix*vec4(position,1.0); vW = w.xyz; gl_Position = projectionMatrix*viewMatrix*w; }`,
    fragmentShader: `precision highp float; in float vShade; in vec3 vW; out vec4 o; uniform float dim; uniform vec3 fogColor; uniform vec3 cam; uniform vec3 tint; uniform float fogEnd;
      void main(){ vec3 c = vec3(vShade) * dim * tint; float d = length(vW.xz - cam.xz); float f = smoothstep(fogEnd * 1.0, fogEnd * 2.4, d); c = mix(c, fogColor, f); o = vec4(c, 0.8 * (1.0 - smoothstep(fogEnd * 1.6, fogEnd * 3.2, d))); }`,
    transparent: true,
    depthWrite: false,
  });

  // Fancy clouds: each opaque texel is a 12 x 4 x 12 box, with internal faces removed.
  function buildClouds(cx, cz, offsetX) {
    const { data, w, h } = cloudState;
    const pos = [], shade = [];
    const S = 12, H = 4;
    const R = 48;
    const baseI = Math.floor((cx + offsetX) / S), baseJ = Math.floor(cz / S);
    const on = (i, j) => data[(((j % h) + h) % h * w + (((i % w) + w) % w)) * 4 + 3] > 10;
    const quad = (a, b, c, d, s) => { pos.push(...a, ...b, ...c, ...a, ...c, ...d); for (let k = 0; k < 6; k++) shade.push(s); };
    for (let di = -R; di <= R; di++) {
      for (let dj = -R; dj <= R; dj++) {
        const i = baseI + di, j = baseJ + dj;
        if (!on(i, j)) continue;
        const x0 = i * S - offsetX, x1 = x0 + S, z0 = j * S, z1 = z0 + S, y0 = 0, y1 = H;
        quad([x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0], 1.0);
        quad([x0, y0, z1], [x0, y0, z0], [x1, y0, z0], [x1, y0, z1], 0.7);
        if (!on(i, j - 1)) quad([x1, y1, z0], [x1, y0, z0], [x0, y0, z0], [x0, y1, z0], 0.8);
        if (!on(i, j + 1)) quad([x0, y1, z1], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], 0.8);
        if (!on(i - 1, j)) quad([x0, y1, z0], [x0, y0, z0], [x0, y0, z1], [x0, y1, z1], 0.9);
        if (!on(i + 1, j)) quad([x1, y1, z1], [x1, y0, z1], [x1, y0, z0], [x1, y1, z0], 0.9);
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('shade', new THREE.Float32BufferAttribute(shade, 1));
    return g;
  }

  return {
    group, dome, sun, clouds, ready, cloudMat,
    update(camera, opts) {
      group.position.copy(camera.position);
      const sd = sharedUniforms.sunDir.value;
      sun.position.copy(sd).multiplyScalar(600);
      sun.scale.setScalar(600 * 0.3 * 1.0);
      sun.lookAt(camera.position.clone().add(new THREE.Vector3()).sub(group.position).add(new THREE.Vector3()));
      sun.quaternion.copy(camera.quaternion);
      dome.material.uniforms.dim.value = opts.skyDim ?? 1.0;
      dome.material.uniforms.sunset.value = opts.sunset ?? 0.0;
      if (opts.skyTop) dome.material.uniforms.skyTop.value.set(opts.skyTop);
      // Clouds live in world space; rebuild when the camera has moved far enough.
      if (cloudState.data && opts.clouds !== false) {
        const camWorldX = camera.position.x, camWorldZ = camera.position.z;
        const off = opts.cloudOffset || 0;
        const key = Math.round(camWorldX / 96) + ',' + Math.round(camWorldZ / 96) + ',' + Math.round(off / 12);
        if (cloudState.key !== key) {
          cloudState.key = key;
          if (cloudState.mesh) { clouds.remove(cloudState.mesh); cloudState.mesh.geometry.dispose(); }
          cloudState.mesh = new THREE.Mesh(buildClouds(camWorldX, camWorldZ, Math.round(off / 12) * 12), cloudMat);
          cloudState.mesh.frustumCulled = false;
          clouds.add(cloudState.mesh);
        }
        clouds.position.set(-camera.position.x - (off - Math.round(off / 12) * 12), 192 - sceneOriginY - camera.position.y + 0.33, -camera.position.z);
        clouds.visible = true;
      } else {
        clouds.visible = false;
      }
      cloudMat.uniforms.dim.value = opts.cloudDim ?? 1.0;
    },
  };
}
