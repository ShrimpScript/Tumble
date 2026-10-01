// 2D overlays drawn the way the game draws its GUI: the bitmap font from the font
// definition, GUI scale chosen like Window.calculateScale, and the HUD, chat, title,
// death screen and chest screen laid out with vanilla's own coordinates and textures.

const ASSET = '/assets/';

async function img(url) {
  const i = new Image();
  i.src = url;
  await i.decode();
  return i;
}

export class Overlay {
  constructor(w, h) {
    this.w = w;
    this.h = h;
    // Window.calculateScale with "auto": largest scale that keeps a 320x240 GUI.
    let s = 1;
    while (s < 8 && w / (s + 1) >= 320 && h / (s + 1) >= 240) s++;
    this.scale = s;
    this.gw = Math.floor(w / s);
    this.gh = Math.floor(h / s);
    this.glyphs = new Map();
    this.tinted = new Map();
  }

  async load() {
    const def = await (await fetch(ASSET + 'font/include/default.json')).json();
    for (const p of def.providers) {
      if (p.type !== 'bitmap' || p.file.includes('accented')) continue;
      const file = p.file.replace('minecraft:', '');
      const image = await img(ASSET + 'textures/' + file);
      const rows = p.chars;
      const cw = image.width / [...rows[0]].length;
      const ch = image.height / rows.length;
      const c = document.createElement('canvas');
      c.width = image.width; c.height = image.height;
      const cx = c.getContext('2d');
      cx.drawImage(image, 0, 0);
      const data = cx.getImageData(0, 0, image.width, image.height).data;
      const scale = (p.height ?? 8) / ch;
      rows.forEach((row, r) => {
        [...row].forEach((chr, col) => {
          if (chr === '\u0000' || this.glyphs.has(chr)) return;
          let width = 0;
          for (let x = cw - 1; x >= 0 && !width; x--) {
            for (let y = 0; y < ch; y++) {
              if (data[((r * ch + y) * image.width + col * cw + x) * 4 + 3] > 0) { width = x + 1; break; }
            }
          }
          this.glyphs.set(chr, { image, sx: col * cw, sy: r * ch, sw: cw, sh: ch, scale,
            advance: Math.floor(0.5 + width * scale) + 1, ascent: p.ascent });
        });
      });
    }
    this.icons = await img(ASSET + 'textures/gui/icons.png');
    this.widgets = await img(ASSET + 'textures/gui/widgets.png');
    this.chest = await img(ASSET + 'textures/gui/container/generic_54.png');
    this.items = {};
    const face = new FontFace('PlexSB', 'url(/ui/IBMPlexSans-SemiBold.ttf)');
    await face.load();
    document.fonts.add(face);
    const reg = new FontFace('PlexR', 'url(/ui/IBMPlexSans-1.ttf)');
    await reg.load();
    document.fonts.add(reg);
    return this;
  }

  async item(name, kind = 'item') {
    if (!this.items[name]) {
      if (kind === 'block') {
        const [top, side, front] = name.split('|');
        this.items[name] = { block: true, top: await img(ASSET + 'textures/block/' + top + '.png'),
          side: await img(ASSET + 'textures/block/' + side + '.png'),
          front: await img(ASSET + 'textures/block/' + (front || side) + '.png') };
      } else {
        this.items[name] = { image: await img(ASSET + 'textures/' + name + '.png') };
      }
    }
    return this.items[name];
  }

  // -- text --------------------------------------------------------------------------------

  width(text) {
    let w = 0;
    for (const chr of text) w += chr === ' ' ? 4 : (this.glyphs.get(chr)?.advance ?? 6);
    return w;
  }

  tint(image, color) {
    const key = image.src + color;
    if (!this.tinted.has(key)) {
      const c = document.createElement('canvas');
      c.width = image.width; c.height = image.height;
      const x = c.getContext('2d');
      x.drawImage(image, 0, 0);
      x.globalCompositeOperation = 'source-in';
      x.fillStyle = color;
      x.fillRect(0, 0, c.width, c.height);
      this.tinted.set(key, c);
    }
    return this.tinted.get(key);
  }

  // Font.drawInternal: shadow at +1,+1 with the colour at a quarter brightness.
  text(ctx, text, x, y, color = '#ffffff', shadow = true, scale = this.scale, alpha = 1) {
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.imageSmoothingEnabled = false;
    const draw = (ox, oy, col) => {
      let cx = x;
      for (const chr of text) {
        if (chr === ' ') { cx += 4 * scale; continue; }
        const g = this.glyphs.get(chr);
        if (!g) { cx += 6 * scale; continue; }
        const src = this.tint(g.image, col);
        ctx.drawImage(src, g.sx, g.sy, g.sw, g.sh, cx + ox * scale, y + oy * scale + (7 - g.ascent) * scale,
          g.sw * g.scale * scale, g.sh * g.scale * scale);
        cx += g.advance * scale;
      }
    };
    if (shadow) draw(1, 1, quarter(color));
    draw(0, 0, color);
    ctx.restore();
  }

  centered(ctx, text, cx, y, color, shadow = true, scale = this.scale, alpha = 1) {
    this.text(ctx, text, cx - (this.width(text) * scale) / 2, y, color, shadow, scale, alpha);
  }

  // -- pieces of the game's GUI -----------------------------------------------------------

  blit(ctx, image, x, y, u, v, w, h, s = this.scale) {
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(image, u, v, w, h, x * s, y * s, w * s, h * s);
  }

  // Chat line: ChatComponent draws a 50% black strip behind each message.
  chat(ctx, lines, alpha = 1) {
    const s = this.scale;
    const lineH = 9;
    const bottom = this.gh - 40;
    lines.forEach((line, i) => {
      const y = bottom - (lines.length - i) * lineH;
      ctx.fillStyle = `rgba(0,0,0,${0.5 * alpha})`;
      ctx.fillRect(2 * s, y * s, 320 * s, lineH * s);
      this.text(ctx, line, 4 * s, (y + 1) * s, '#ffffff', true, s, alpha);
    });
  }

  // /title: the title at 4x, the subtitle at 2x, centred around the middle of the screen.
  title(ctx, title, subtitle, alpha = 1) {
    const s = this.scale;
    const cx = this.w / 2, cy = this.h / 2;
    // Gui.renderTitle: title drawn at -10 under a 4x scale, subtitle at 5 under 2x.
    if (title) this.centered(ctx, title, cx, cy - 40 * s, '#ffffff', true, s * 4, alpha);
    if (subtitle) this.centered(ctx, subtitle, cx, cy + 10 * s, '#ffffff', true, s * 2, alpha);
  }

  // Caption in the lower third, subtitle-sized, for the narration.
  caption(ctx, text, alpha = 1, color = '#ffffff', top = false) {
    const s = this.scale;
    this.centered(ctx, text, this.w / 2, this.h * (top ? 0.12 : 0.78), color, true, s * 2, alpha);
  }

  hud(ctx, st) {
    const s = this.scale, W = this.gw, H = this.gh;
    const x0 = Math.floor(W / 2) - 91;
    // Crosshair (drawn inverted in game; white reads the same on these scenes).
    if (st.crosshair) {
      ctx.globalCompositeOperation = 'difference';
      this.blit(ctx, this.icons, Math.floor((W - 15) / 2), Math.floor((H - 15) / 2), 0, 0, 15, 15);
      ctx.globalCompositeOperation = 'source-over';
    }
    // Hotbar and selection.
    this.blit(ctx, this.widgets, x0, H - 22, 0, 0, 182, 22);
    this.blit(ctx, this.widgets, x0 - 1 + (st.selected ?? 0) * 20, H - 22 - 1, 0, 22, 24, 24);
    (st.hotbar || []).forEach((it, i) => { if (it) this.slotItem(ctx, it, x0 + 3 + i * 20, H - 19, st.pop?.[i]); });
    // XP bar.
    const xpY = H - 32 + 3;
    this.blit(ctx, this.icons, x0, xpY, 0, 64, 182, 5);
    const prog = Math.floor((st.xp ?? 0.3) * 183);
    if (prog > 0) this.blit(ctx, this.icons, x0, xpY, 0, 69, prog, 5);
    if (st.level) {
      const t = String(st.level);
      const tx = (W - this.width(t)) / 2, ty = H - 31 - 4;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) this.text(ctx, t, (tx + dx) * s, (ty + dy) * s, '#000000', false);
      this.text(ctx, t, tx * s, ty * s, '#80ff20', false);
    }
    // Hearts: Gui.renderHearts. Containers, then full / half hearts.
    const health = st.health ?? 20;
    const hy = H - 39;
    for (let i = 9; i >= 0; i--) {
      const x = x0 + i * 8;
      let y = hy;
      if (health <= 4 && st.jiggle) y += Math.floor(st.jiggle(i));
      const blink = st.blink ? 1 : 0;
      this.blit(ctx, this.icons, x, y, 16 + blink * 9, 0, 9, 9);
      if (i * 2 + 1 < health) this.blit(ctx, this.icons, x, y, 52, 0, 9, 9);
      else if (i * 2 + 1 === Math.ceil(health) && health % 2 !== 0) this.blit(ctx, this.icons, x, y, 61, 0, 9, 9);
      if (st.lost && i * 2 + 1 < st.lost && i * 2 + 1 >= health) this.blit(ctx, this.icons, x, y, 70, 0, 9, 9);
    }
    // Food, right to left.
    const food = st.food ?? 20;
    for (let i = 0; i < 10; i++) {
      const x = x0 + 182 - i * 8 - 9;
      this.blit(ctx, this.icons, x, hy, 16, 27, 9, 9);
      if (i * 2 + 1 < food) this.blit(ctx, this.icons, x, hy, 52, 27, 9, 9);
      else if (i * 2 + 1 === food) this.blit(ctx, this.icons, x, hy, 61, 27, 9, 9);
    }
  }

  // An item as the GUI draws it at 16x16, with the stack count in the corner.
  slotItem(ctx, it, gx, gy, pop = 0) {
    const s = this.scale;
    const data = this.items[it.name];
    if (!data) return;
    ctx.save();
    ctx.imageSmoothingEnabled = false;
    const sc = 1 + (pop || 0) * 0.4;
    ctx.translate((gx + 8) * s, (gy + 8) * s);
    ctx.scale(sc, sc);
    if (data.block) this.isoBlock(ctx, data, 16 * s);
    else ctx.drawImage(data.image, -8 * s, -8 * s, 16 * s, 16 * s);
    ctx.restore();
    if (it.count > 1) {
      const t = String(it.count);
      this.text(ctx, t, (gx + 19 - 2 - this.width(t)) * s, (gy + 6 + 3) * s, '#ffffff', true);
    }
  }

  // Block items in the GUI: an isometric cube, top lit, the two sides shaded.
  isoBlock(ctx, d, size) {
    const r = size * 0.42, k = r * 0.866;
    const T = [0, -r], R = [k, -r / 2], C = [0, 0], Lp = [-k, -r / 2];
    const down = (p) => [p[0], p[1] + r];
    const face = (image, pts, shade) => {
      const [a, b, , c] = pts;
      ctx.save();
      ctx.beginPath();
      pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
      ctx.closePath();
      ctx.clip();
      ctx.transform((b[0] - a[0]) / 16, (b[1] - a[1]) / 16, (c[0] - a[0]) / 16, (c[1] - a[1]) / 16, a[0], a[1]);
      ctx.drawImage(image, 0, 0, 16, 16, -0.05, -0.05, 16.1, 16.1);
      ctx.restore();
      if (shade < 1) {
        ctx.save();
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
        ctx.closePath();
        ctx.fillStyle = `rgba(0,0,0,${1 - shade})`;
        ctx.fill();
        ctx.restore();
      }
    };
    face(d.top, [Lp, T, R, C], 1.0);
    face(d.side, [Lp, C, down(C), down(Lp)], 0.8);
    face(d.front, [C, R, down(R), down(C)], 0.6);
  }

  // DeathScreen.render, 1.20.1.
  death(ctx, st) {
    const s = this.scale, W = this.gw, H = this.gh;
    const g = ctx.createLinearGradient(0, 0, 0, this.h);
    g.addColorStop(0, 'rgba(80,0,0,0.376)');
    g.addColorStop(1, 'rgba(128,48,48,0.627)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, this.w, this.h);
    this.centered(ctx, 'You Died!', this.w / 2, 30 * 2 * s, '#ffffff', true, s * 2);
    if (st.message) this.centered(ctx, st.message, this.w / 2, 85 * s, '#ffffff', true);
    const score = 'Score: ';
    const sw = this.width(score + '0');
    this.text(ctx, score, (W / 2 - sw / 2) * s, 100 * s, '#ffffff');
    this.text(ctx, '0', (W / 2 - sw / 2 + this.width(score)) * s, 100 * s, '#ffff55');
    const bx = Math.floor(W / 2) - 100;
    const by1 = Math.floor(H / 4) + 72, by2 = Math.floor(H / 4) + 96;
    this.button(ctx, bx, by1, 'Respawn', st.enabled, st.hover === 0);
    this.button(ctx, bx, by2, 'Title Screen', st.enabled, st.hover === 1);
    if (st.cursor) this.cursor(ctx, st.cursor[0], st.cursor[1]);
    return { respawn: [bx + 100, by1 + 10] };
  }

  // AbstractWidget: the 200x20 button texture split in two halves.
  button(ctx, x, y, label, enabled = true, hover = false) {
    const v = !enabled ? 46 : hover ? 86 : 66;
    this.blit(ctx, this.widgets, x, y, 0, v, 100, 20);
    this.blit(ctx, this.widgets, x + 100, y, 100, v, 100, 20);
    this.centered(ctx, label, (x + 100) * this.scale, (y + 6) * this.scale, enabled ? (hover ? '#ffffa0' : '#e0e0e0') : '#a0a0a0');
  }

  cursor(ctx, gx, gy) {
    const s = this.scale / 2;
    const px = gx * this.scale, py = gy * this.scale;
    const shape = [
      'X', 'XX', 'XWX', 'XWWX', 'XWWWX', 'XWWWWX', 'XWWWWWX', 'XWWWWWWX', 'XWWWWWWWX', 'XWWWWWWWWX',
      'XWWWWWXXXX', 'XWWXWWX', 'XWX XWWX', 'XX  XWWX', 'X    XWWX', '     XWWX', '      XX',
    ];
    shape.forEach((row, y) => [...row].forEach((c, x) => {
      if (c === ' ') return;
      ctx.fillStyle = c === 'X' ? '#000' : '#fff';
      ctx.fillRect(px + x * s, py + y * s, s, s);
    }));
  }

  // ContainerScreen for a six-row chest, as ChestMenu.sixRows lays out its slots.
  chestScreen(ctx, st) {
    const s = this.scale, W = this.gw, H = this.gh;
    const g = ctx.createLinearGradient(0, 0, 0, this.h);
    g.addColorStop(0, 'rgba(16,16,16,0.753)');
    g.addColorStop(1, 'rgba(16,16,16,0.816)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, this.w, this.h);
    const rows = 6, iw = 176, ih = 114 + rows * 18;
    const x = Math.floor((W - iw) / 2), y = Math.floor((H - ih) / 2);
    this.blit(ctx, this.chest, x, y, 0, 0, iw, rows * 18 + 17);
    this.blit(ctx, this.chest, x, y + rows * 18 + 17, 0, 126, iw, 96);
    this.text(ctx, st.title, (x + 8) * s, (y + 6) * s, '#404040', false);
    this.text(ctx, 'Inventory', (x + 8) * s, (y + ih - 96 + 2) * s, '#404040', false);
    const slotPos = [];
    for (let i = 0; i < rows * 9; i++) slotPos.push([x + 8 + (i % 9) * 18, y + 18 + Math.floor(i / 9) * 18]);
    const k = (rows - 4) * 18;
    for (let i = 0; i < 27; i++) slotPos.push([x + 8 + (i % 9) * 18, y + 103 + Math.floor(i / 9) * 18 + k]);
    for (let i = 0; i < 9; i++) slotPos.push([x + 8 + i * 18, y + 161 + k]);
    (st.container || []).forEach((it, i) => it && this.slotItem(ctx, it, slotPos[i][0], slotPos[i][1], it.pop));
    (st.inventory || []).forEach((it, i) => it && this.slotItem(ctx, it, slotPos[54 + i][0], slotPos[54 + i][1], it.pop));
    (st.hotbar || []).forEach((it, i) => it && this.slotItem(ctx, it, slotPos[81 + i][0], slotPos[81 + i][1], it.pop));
    if (st.hoverSlot !== undefined && st.hoverSlot >= 0) {
      const [hx, hy] = slotPos[st.hoverSlot];
      ctx.fillStyle = 'rgba(255,255,255,0.5)';
      ctx.fillRect(hx * s, hy * s, 16 * s, 16 * s);
    }
    if (st.cursor) this.cursor(ctx, st.cursor[0], st.cursor[1]);
    return slotPos;
  }

  // The mod's logo, rebuilt letter by letter in its own typeface (IBM Plex Sans
  // SemiBold, fitted against logo.png) so each letter can fall into place.
  logo(ctx, cx, cy, size, letters, alpha = 1) {
    // Letter centres and rotations measured from src/main/resources/logo.png (512 px).
    const L = [['T', 73, 166.5, 0], ['U', 134, 172.5, 4], ['M', 205.5, 193.5, 11],
      ['B', 290, 226.5, 21.5], ['L', 354, 277, 32], ['E', 423, 338.5, 46]];
    const k = size / 512;
    const ox = 248, oy = 252;   // centre of the word's bounding box in the logo
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.font = `${103.35 * k}px PlexSB`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    L.forEach(([ch, lx, ly, rot], i) => {
      const st = letters ? letters[i] : { dx: 0, dy: 0, rot: 0, a: 1 };
      if (st.a <= 0) return;
      const m = ctx.measureText(ch);
      const inkMid = (m.actualBoundingBoxAscent - m.actualBoundingBoxDescent) / 2;
      ctx.save();
      ctx.globalAlpha = alpha * st.a;
      ctx.translate(cx + (lx - ox) * k + st.dx * k, cy + (ly - oy) * k + st.dy * k);
      ctx.rotate(((rot + st.rot) * Math.PI) / 180);
      ctx.fillStyle = ch === 'E' ? 'rgb(224,96,58)' : 'rgb(232,234,239)';
      ctx.fillText(ch, 0, inkMid);
      ctx.restore();
    });
    ctx.restore();
  }

  plain(ctx, text, x, y, px, color = '#e8eaef', weight = 'PlexR', align = 'center', alpha = 1) {
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.font = `${px}px ${weight}`;
    ctx.fillStyle = color;
    ctx.textAlign = align;
    ctx.textBaseline = 'middle';
    ctx.fillText(text, x, y);
    ctx.restore();
  }
}

function quarter(color) {
  const n = parseInt(color.slice(1), 16);
  const r = ((n >> 16) & 255) >> 2, g = ((n >> 8) & 255) >> 2, b = (n & 255) >> 2;
  return '#' + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
}
