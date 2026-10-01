// GLSL shared by every world material: vanilla's lightmap, sun shadows and fog.

export const LIGHTMAP_GLSL = /* glsl */`
uniform float daylight;
uniform float gammaSetting;
uniform float minLight;
uniform float exposure;
uniform vec3 sunColor;
uniform float shadowStrength;
uniform float emissiveBoost;
uniform float flash;
uniform vec3 flashPos;
uniform float flashRadius;

// LightTexture.getBrightness for the overworld (ambient light 0).
float mcBright(float level) {
  float f = clamp(level / 15.0, 0.0, 1.0);
  return f / (4.0 - 3.0 * f);
}
vec3 notGamma(vec3 x) { vec3 y = 1.0 - x; return 1.0 - y * y * y * y; }

// LightTexture.updateLightTexture, per pixel, plus sun visibility for shadows.
vec3 lightmap(float sky, float blk, float sunVis) {
  float f1 = daylight * 0.95 + 0.05;
  vec3 skyTint = mix(vec3(daylight, daylight, 1.0), vec3(1.0), 0.35);
  float s = mcBright(sky) * f1;
  float b = mcBright(blk) * 1.5;
  vec3 c = vec3(b, b * ((b * 0.6 + 0.4) * 0.6 + 0.4), b * (b * b * 0.6 + 0.4));
  vec3 shadowCol = vec3(1.0 - shadowStrength) * vec3(0.88, 0.94, 1.08);
  c += skyTint * s * mix(shadowCol, sunColor, sunVis);
  c = mix(c, vec3(0.75), 0.04);
  c = clamp(c, 0.0, 1.0);
  c = mix(c, notGamma(c), gammaSetting);
  c = mix(c, vec3(0.75), 0.04);
  return max(clamp(c, 0.0, 1.0), vec3(minLight)) * exposure;
}
`;

export const SHADOW_GLSL = /* glsl */`
uniform sampler2D shadowMap;
uniform mat4 shadowMatrix;
uniform float shadowTexel;
uniform float shadowOn;
uniform vec3 sunDir;

float sunShadow(vec3 world, vec3 n, float sky) {
  if (shadowOn < 0.5 || sky < 0.5) return 1.0;
  float facing = dot(n, sunDir);
  if (facing <= 0.02) return 0.0;
  vec4 sc = shadowMatrix * vec4(world + n * 0.04, 1.0);
  vec3 p = sc.xyz / sc.w * 0.5 + 0.5;
  if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
  float bias = 0.0006;
  float lit = 0.0;
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      float d = texture(shadowMap, p.xy + vec2(float(i), float(j)) * shadowTexel * 1.25).r;
      lit += (p.z - bias <= d) ? 1.0 : 0.0;
    }
  }
  return lit / 9.0 * smoothstep(0.02, 0.2, facing);
}
`;

export const FOG_GLSL = /* glsl */`
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform vec3 cameraWorld;
uniform float caveFog;

vec3 applyFog(vec3 col, vec3 world, float depth) {
  vec3 d = world - cameraWorld;
  float dist = max(length(d.xz), abs(d.y));
  float f = dist <= fogStart ? 0.0 : (dist < fogEnd ? smoothstep(fogStart, fogEnd, dist) : 1.0);
  col = mix(col, fogColor, f);
  if (caveFog > 0.0) {
    float cf = 1.0 - exp(-length(d) * 0.012 * caveFog);
    col = mix(col, vec3(0.02, 0.02, 0.03), cf);
  }
  if (flash > 0.0) {
    float k = flash * max(0.0, 1.0 - length(world - flashPos) / flashRadius);
    col += vec3(1.0, 0.9, 0.75) * k;
  }
  return col;
}
`;
