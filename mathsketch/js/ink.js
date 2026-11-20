/* SketchSolve — a drawing board.
 * Captures pen / finger / mouse strokes, supports an eraser, undo and redo.
 * What the strokes mean is up to the page using it. */
(function (root) {
  'use strict';

  let nextId = 1;

  class InkBoard {
    /**
     * wrap: element to fill.
     * opts.kindFor(tool) → kind string for a new stroke (or null to ignore)
     * opts.onStroke(stroke, board) — called when a stroke is finished
     * opts.onChange(board) — after any change (stroke, erase, undo…)
     * opts.drawBelow(ctx), opts.drawAbove(ctx), opts.styleFor(stroke)
     */
    constructor(wrap, opts) {
      this.wrap = wrap;
      this.opts = opts;
      this.canvas = document.createElement('canvas');
      this.canvas.className = 'ink';
      this.overlay = document.createElement('div');
      this.overlay.className = 'ink-overlay';
      wrap.append(this.canvas, this.overlay);
      this.ctx = this.canvas.getContext('2d');
      this.strokes = [];
      this.undoStack = [];
      this.redoStack = [];
      this.tool = 'pen';
      this.current = null;
      this.penSeen = false;
      this.erasing = false;
      this.dpr = 1;
      this._bind();
      new ResizeObserver(() => this.resize()).observe(wrap);
      this.resize();
    }

    static setNextId(n) { nextId = Math.max(nextId, n); }

    resize() {
      const r = this.wrap.getBoundingClientRect();
      this.w = Math.max(50, r.width);
      this.h = Math.max(50, r.height);
      this.dpr = window.devicePixelRatio || 1;
      this.canvas.width = Math.round(this.w * this.dpr);
      this.canvas.height = Math.round(this.h * this.dpr);
      this.canvas.style.width = this.w + 'px';
      this.canvas.style.height = this.h + 'px';
      this.render();
    }

    _pos(e) {
      const r = this.canvas.getBoundingClientRect();
      return [e.clientX - r.left, e.clientY - r.top];
    }

    _bind() {
      const c = this.canvas;
      c.addEventListener('pointerdown', (e) => {
        if (e.pointerType === 'pen') this.penSeen = true;
        if (e.pointerType === 'touch' && this.penSeen) return; // palm rejection
        if (e.button !== undefined && e.button > 0 && e.pointerType === 'mouse') return;
        e.preventDefault();
        c.setPointerCapture(e.pointerId);
        this.pointerId = e.pointerId;
        if (this.tool === 'erase') {
          this.erasing = true;
          this._snapshot();
          this._eraseAt(this._pos(e));
          return;
        }
        const kind = this.opts.kindFor ? this.opts.kindFor(this.tool) : 'ink';
        if (!kind) return;
        this.current = { id: nextId++, kind, pts: [this._pos(e)] };
        this.render();
      });
      c.addEventListener('pointermove', (e) => {
        if (e.pointerId !== this.pointerId) return;
        if (this.erasing) { this._eraseAt(this._pos(e)); return; }
        if (!this.current) return;
        const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
        for (const ev of evs) {
          const p = this._pos(ev);
          const last = this.current.pts[this.current.pts.length - 1];
          if (Math.hypot(p[0] - last[0], p[1] - last[1]) >= 0.8) this.current.pts.push(p);
        }
        this.render();
      });
      const end = (e) => {
        if (e.pointerId !== this.pointerId) return;
        this.pointerId = null;
        if (this.erasing) { this.erasing = false; this._changed(); return; }
        const s = this.current;
        this.current = null;
        if (!s) return;
        if (s.pts.length === 1) s.pts.push([s.pts[0][0] + 0.5, s.pts[0][1] + 0.5]);
        this._snapshot();
        this.strokes.push(s);
        if (this.opts.onStroke) this.opts.onStroke(s, this);
        this._changed();
      };
      c.addEventListener('pointerup', end);
      c.addEventListener('pointercancel', end);
      c.addEventListener('contextmenu', (e) => e.preventDefault());
    }

    _eraseAt(p) {
      const R = 12;
      const before = this.strokes.length;
      this.strokes = this.strokes.filter((s) => {
        for (let i = 0; i < s.pts.length; i++) {
          const a = s.pts[i], b = s.pts[Math.min(i + 1, s.pts.length - 1)];
          if (distSeg(p, a, b) < R) return false;
        }
        return true;
      });
      if (this.strokes.length !== before) this.render();
    }

    _snapshot() {
      this.undoStack.push(this.strokes.slice());
      if (this.undoStack.length > 200) this.undoStack.shift();
      this.redoStack = [];
    }

    _changed() {
      this.render();
      if (this.opts.onChange) this.opts.onChange(this);
    }

    setStrokes(strokes, record) {
      if (record) this._snapshot();
      this.strokes = strokes.slice();
      for (const s of strokes) if (s.id >= nextId) nextId = s.id + 1;
      this._changed();
    }

    removeStrokes(ids, record) {
      const set = new Set(ids);
      if (record !== false) this._snapshot();
      this.strokes = this.strokes.filter((s) => !set.has(s.id));
      this._changed();
    }

    undo() {
      if (!this.undoStack.length) return;
      this.redoStack.push(this.strokes);
      this.strokes = this.undoStack.pop();
      this._changed();
    }

    redo() {
      if (!this.redoStack.length) return;
      this.undoStack.push(this.strokes);
      this.strokes = this.redoStack.pop();
      this._changed();
    }

    clear() {
      if (!this.strokes.length) return;
      this._snapshot();
      this.strokes = [];
      this._changed();
    }

    render() {
      const ctx = this.ctx;
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.clearRect(0, 0, this.w, this.h);
      if (this.opts.drawBelow) this.opts.drawBelow(ctx, this);
      const all = this.current ? this.strokes.concat([this.current]) : this.strokes;
      for (const s of all) {
        const st = (this.opts.styleFor && this.opts.styleFor(s, this)) || {};
        drawStroke(ctx, s.pts, st.color || '#223', st.width || 2.6, st.dash);
      }
      if (this.opts.drawAbove) this.opts.drawAbove(ctx, this);
    }
  }

  function distSeg(p, a, b) {
    const vx = b[0] - a[0], vy = b[1] - a[1];
    const L2 = vx * vx + vy * vy;
    let t = L2 ? ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / L2 : 0;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(p[0] - (a[0] + t * vx), p[1] - (a[1] + t * vy));
  }

  // Smooth stroke through midpoints with quadratic curves.
  function drawStroke(ctx, pts, color, width, dash) {
    if (!pts.length) return;
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    if (dash) ctx.setLineDash(dash);
    ctx.beginPath();
    ctx.moveTo(pts[0][0], pts[0][1]);
    if (pts.length < 3) {
      for (const p of pts) ctx.lineTo(p[0], p[1]);
    } else {
      for (let i = 1; i < pts.length - 1; i++) {
        const mx = (pts[i][0] + pts[i + 1][0]) / 2, my = (pts[i][1] + pts[i + 1][1]) / 2;
        ctx.quadraticCurveTo(pts[i][0], pts[i][1], mx, my);
      }
      const l = pts[pts.length - 1];
      ctx.lineTo(l[0], l[1]);
    }
    ctx.stroke();
    ctx.restore();
  }

  root.InkBoard = InkBoard;
  root.drawStroke = drawStroke;
})(typeof self !== 'undefined' ? self : this);
