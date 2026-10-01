// Terrain: chunk meshes baked by world/mesher.py, drawn with vanilla's lighting model.
import * as THREE from 'three';
import { LIGHTMAP_GLSL, SHADOW_GLSL, FOG_GLSL } from './shaderlib.js';

export async function loadTerrain(base, prefix = 'terrain', shared = null) {
  const meta = await (await fetch(base + prefix + '.json')).json();
  const bin = await (await fetch(base + prefix + '.bin')).arrayBuffer();
  const view = (name, Type) => {
    const [off, n] = meta.arrays[name];
    return new Type(bin, off, n);
  };
  const pos = view('pos', Float32Array);
  const uv = view('uv', Float32Array);
  const layer = view('layer', Uint16Array);
  const light = view('light', Uint8Array);
  const tint = view('tint', Uint8Array);

  let tex, animTex, texMeta;
  if (shared) {
    ({ tex, animTex, texMeta } = shared);
  } else {
    texMeta = await (await fetch(base + 'blocks.json')).json();
    const rgba = new Uint8Array(await (await fetch(base + 'blocks.rgba')).arrayBuffer());
    tex = new THREE.DataArrayTexture(rgba, 16, 16, texMeta.layers);
    tex.format = THREE.RGBAFormat;
    tex.type = THREE.UnsignedByteType;
    tex.magFilter = THREE.NearestFilter;
    tex.minFilter = THREE.NearestMipmapLinearFilter;
    tex.generateMipmaps = true;
    tex.colorSpace = THREE.NoColorSpace;
    tex.needsUpdate = true;

    // Animation table: per first layer, frame count and frame time in ticks.
    const animData = new Float32Array(texMeta.layers * 4);
    for (const [first, [n, ft]] of Object.entries(texMeta.anim)) {
      animData[+first * 4] = n;
      animData[+first * 4 + 1] = ft;
    }
    animTex = new THREE.DataTexture(animData, texMeta.layers, 1, THREE.RGBAFormat, THREE.FloatType);
    animTex.needsUpdate = true;
  }

  let maxVerts = 0;
  for (const c of meta.chunks) maxVerts = Math.max(maxVerts, c.count);
  const quads = maxVerts / 4;
  const index = new Uint32Array(quads * 6);
  for (let q = 0; q < quads; q++) {
    const v = q * 4, i = q * 6;
    index[i] = v; index[i + 1] = v + 1; index[i + 2] = v + 2;
    index[i + 3] = v; index[i + 4] = v + 2; index[i + 5] = v + 3;
  }
  const indexAttr = new THREE.BufferAttribute(index, 1);

  const group = new THREE.Group();
  const solids = [];
  const mats = shared ? shared.mats : {
    solid: terrainMaterial(tex, animTex, false),
    translucent: terrainMaterial(tex, animTex, true),
  };
  for (const c of meta.chunks) {
    const g = new THREE.BufferGeometry();
    const a = c.first, n = c.count;
    g.setAttribute('position', new THREE.BufferAttribute(pos.subarray(a * 3, (a + n) * 3), 3));
    g.setAttribute('uv', new THREE.BufferAttribute(uv.subarray(a * 2, (a + n) * 2), 2));
    g.setAttribute('layer', new THREE.BufferAttribute(layer.subarray(a, a + n), 1));
    g.setAttribute('light', new THREE.BufferAttribute(light.subarray(a * 4, (a + n) * 4), 4, true));
    g.setAttribute('tint', new THREE.BufferAttribute(tint.subarray(a * 3, (a + n) * 3), 3, true));
    g.setIndex(indexAttr);
    g.setDrawRange(0, (n / 4) * 6);
    g.computeBoundingBox();
    g.computeBoundingSphere();
    const mesh = new THREE.Mesh(g, mats[c.kind]);
    mesh.frustumCulled = true;
    if (c.kind === 'translucent') mesh.renderOrder = 10;
    mesh.userData.kind = c.kind;
    mesh.userData.chunk = c.cx + ',' + c.cz;
    group.add(mesh);
    if (c.kind === 'solid') solids.push(mesh);
  }
  return { group, solids, mats, tex, animTex, meta, texMeta };
}

function terrainMaterial(tex, animTex, translucent) {
  return new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3,
    uniforms: Object.assign({
      blocks: { value: tex },
      animTex: { value: animTex },
      ticks: { value: 0 },
    }, sharedUniforms),
    vertexShader: /* glsl */`
      in float layer;
      in vec4 light;
      in vec3 tint;
      out vec2 vUv;
      flat out float vLayer;
      out vec4 vLight;
      out vec3 vTint;
      out vec3 vWorld;
      out float vDepth;
      uniform sampler2D animTex;
      uniform float ticks;
      void main() {
        vUv = uv;
        vec4 a = texelFetch(animTex, ivec2(int(layer), 0), 0);
        float l = layer;
        if (a.x > 1.0) l += mod(floor(ticks / max(a.y, 1.0)), a.x);
        vLayer = l;
        vLight = light;
        vTint = tint;
        vec4 w = modelMatrix * vec4(position, 1.0);
        vWorld = w.xyz;
        vec4 mv = viewMatrix * w;
        vDepth = -mv.z;
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: /* glsl */`
      precision highp float;
      precision highp sampler2DArray;
      uniform sampler2DArray blocks;
      in vec2 vUv;
      flat in float vLayer;
      in vec4 vLight;
      in vec3 vTint;
      in vec3 vWorld;
      in float vDepth;
      out vec4 outColor;
      ${LIGHTMAP_GLSL}
      ${SHADOW_GLSL}
      ${FOG_GLSL}
      void main() {
        vec4 t = texture(blocks, vec3(vUv, vLayer));
        ${translucent ? 'if (t.a < 0.01) discard;' : 'if (t.a < 0.5) discard;'}
        vec3 base = t.rgb * vTint;
        vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
        float sky = vLight.x * 15.0;
        float blk = vLight.y * 15.0;
        float sun = sunShadow(vWorld, n, sky);
        vec3 lm = lightmap(sky, blk, sun);
        vec3 col = base * lm * vLight.z;
        if (vLight.w > 0.5) col = base * max(lm, vec3(1.0)) * max(vLight.z, 0.85) * emissiveBoost;
        col = applyFog(col, vWorld, vDepth);
        outColor = vec4(col, ${translucent ? 't.a * 0.72' : '1.0'});
      }`,
    transparent: translucent,
    depthWrite: !translucent,
    side: translucent ? THREE.DoubleSide : THREE.FrontSide,
  });
}

// Uniforms every world material shares, updated once per frame.
export const sharedUniforms = {
  daylight: { value: 1.0 },          // sky darkening, 1 at noon
  gammaSetting: { value: 0.5 },      // the brightness slider
  emissiveBoost: { value: 1.0 },
  sunDir: { value: new THREE.Vector3(0.3, 0.8, 0.2).normalize() },
  sunColor: { value: new THREE.Color(1.0, 0.95, 0.85) },
  shadowStrength: { value: 0.45 },
  shadowMap: { value: null },
  shadowMatrix: { value: new THREE.Matrix4() },
  shadowTexel: { value: 1 / 4096 },
  shadowOn: { value: 0 },
  fogColor: { value: new THREE.Color(0.75, 0.85, 1.0) },
  fogStart: { value: 120 },
  fogEnd: { value: 190 },
  cameraWorld: { value: new THREE.Vector3() },
  caveFog: { value: 0 },
  exposure: { value: 1.0 },
  minLight: { value: 0.0 },
  flash: { value: 0.0 },
  flashPos: { value: new THREE.Vector3() },
  flashRadius: { value: 8.0 },
};
