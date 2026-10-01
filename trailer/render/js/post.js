// Final pass: depth of field, grade, vignette, flash, chromatic kick, tape rewind.
import * as THREE from 'three';

export class Post {
  constructor(renderer, w, h) {
    this.renderer = renderer;
    this.target = new THREE.WebGLRenderTarget(w, h, { depthBuffer: true });
    this.target.depthTexture = new THREE.DepthTexture(w, h);
    this.target.depthTexture.type = THREE.UnsignedIntType;
    this.params = {};
    this.mat = new THREE.ShaderMaterial({
      glslVersion: THREE.GLSL3,
      uniforms: {
        color: { value: this.target.texture },
        depth: { value: this.target.depthTexture },
        res: { value: new THREE.Vector2(w, h) },
        near: { value: 0.05 }, far: { value: 1200 },
        focus: { value: 10 }, aperture: { value: 0 }, maxBlur: { value: 10 },
        saturation: { value: 1.12 }, contrast: { value: 1.06 }, warmth: { value: 0.02 },
        vignette: { value: 0.28 }, flash: { value: 0 }, flashColor: { value: new THREE.Color(1, 1, 1) },
        aberration: { value: 0 }, rewind: { value: 0 }, time: { value: 0 }, desat: { value: 0 },
        fade: { value: 0 }, redTint: { value: 0 }, bright: { value: 1 },
      },
      vertexShader: 'out vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }',
      fragmentShader: /* glsl */`
        precision highp float;
        uniform sampler2D color; uniform sampler2D depth; uniform vec2 res;
        uniform float near, far, focus, aperture, maxBlur;
        uniform float saturation, contrast, warmth, vignette, flash, aberration, rewind, time, desat, fade, redTint, bright;
        uniform vec3 flashColor;
        in vec2 vUv; out vec4 o;
        float linDepth(vec2 uv) {
          float z = texture(depth, uv).r * 2.0 - 1.0;
          return (2.0 * near * far) / (far + near - z * (far - near));
        }
        float coc(vec2 uv) {
          float d = linDepth(uv);
          return clamp(aperture * abs(d - focus) / max(d, 0.001) * res.y / 1080.0 * 18.0, 0.0, maxBlur);
        }
        vec3 sampleColor(vec2 uv) {
          if (aberration > 0.0) {
            vec2 dir = (uv - 0.5) * aberration * 0.012;
            return vec3(texture(color, uv + dir).r, texture(color, uv).g, texture(color, uv - dir).b);
          }
          return texture(color, uv).rgb;
        }
        float h21(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
        void main() {
          vec2 uv = vUv;
          if (rewind > 0.0) {
            float band = step(0.93, fract(uv.y * 3.0 + time * 2.7)) * 0.02 + step(0.985, h21(vec2(floor(uv.y * 140.0), floor(time * 30.0)))) * 0.03;
            uv.x += (band + sin(uv.y * 40.0 + time * 50.0) * 0.002) * rewind;
          }
          vec3 c = sampleColor(uv);
          if (aperture > 0.0) {
            float r = coc(uv);
            if (r > 0.5) {
              vec3 acc = c; float wsum = 1.0;
              for (int i = 0; i < 24; i++) {
                float a = float(i) * 2.39996;
                float rr = sqrt(float(i) + 0.5) / sqrt(24.0) * r;
                vec2 off = vec2(cos(a), sin(a)) * rr / res;
                float rs = coc(uv + off);
                float w = smoothstep(0.0, 1.0, rs / max(rr, 0.001) + 0.3);
                acc += texture(color, uv + off).rgb * w; wsum += w;
              }
              c = acc / wsum;
            }
          }
          c *= bright;
          // Grade.
          float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
          c = mix(vec3(l), c, saturation * (1.0 - desat));
          c = (c - 0.5) * contrast + 0.5;
          c += vec3(warmth, warmth * 0.3, -warmth);
          c = mix(c, c * vec3(1.0, 0.35, 0.35) + vec3(0.12, 0.0, 0.0), redTint);
          if (rewind > 0.0) {
            c += (h21(uv * res + time * 100.0) - 0.5) * 0.12 * rewind;
            c = mix(c, vec3(dot(c, vec3(0.33))), 0.35 * rewind);
          }
          vec2 q = vUv - 0.5;
          c *= 1.0 - vignette * smoothstep(0.25, 0.85, length(q * vec2(1.0, res.y / res.x) * 1.6));
          c = mix(c, flashColor, flash);
          c = mix(c, vec3(0.0), fade);
          // Triangular dither below one 8-bit step, so skies and fades do not band.
          float dn = h21(vUv * res + 0.37) + h21(vUv * res + 11.13) - 1.0;
          c += dn / 255.0;
          o = vec4(clamp(c, 0.0, 1.0), 1.0);
        }`,
      depthTest: false,
      depthWrite: false,
    });
    this.quad = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), this.mat);
    this.scene = new THREE.Scene();
    this.scene.add(this.quad);
    this.cam = new THREE.Camera();
  }

  set(p) {
    const u = this.mat.uniforms;
    const defaults = { focus: 10, aperture: 0, saturation: 1.12, contrast: 1.06, warmth: 0.02, vignette: 0.22,
      flash: 0, aberration: 0, rewind: 0, desat: 0, fade: 0, redTint: 0, bright: 1, maxBlur: 10 };
    for (const [k, v] of Object.entries(defaults)) u[k].value = p[k] ?? v;
    u.flashColor.value.set(p.flashColor ?? 0xffffff);
    u.time.value = p.time ?? 0;
  }

  finish(camera) {
    this.mat.uniforms.near.value = camera.near;
    this.mat.uniforms.far.value = camera.far;
    this.renderer.setRenderTarget(null);
    this.renderer.render(this.scene, this.cam);
  }
}
