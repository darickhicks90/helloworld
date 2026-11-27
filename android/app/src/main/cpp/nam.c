/*
 * Amp captures and cabinet impulses for the audio tracks.
 *
 * A small, allocation-free runner for Neural Amp Modeler (.nam) captures: the
 * classic WaveNet (with or without gating) and LSTM architectures. The model's
 * shape arrives as a few integers and its weights as one float array, already
 * read out of the .nam file on the Kotlin side (data/AmpCaptures.kt), in the order
 * the file stores them. Plus a direct-form FIR for cabinet impulse responses.
 *
 * Everything is set up when a capture is loaded; processing touches only the
 * buffers made then, so it is safe on the audio thread.
 */
#include <jni.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

enum { ACT_TANH = 0, ACT_RELU, ACT_HARDTANH, ACT_SIGMOID, ACT_LEAKY, ACT_FASTTANH };

static inline float sigm(float x) { return 1.f / (1.f + expf(-x)); }
static inline float fast_tanh(float x) {
    // the rational approximation NAM's plugin uses
    const float ax = fabsf(x), x2 = x * x;
    return (x * (2.45550750702956f + 2.45550750702956f * ax + (0.893229853513558f + 0.821226666969744f * ax) * x2)
            / (2.44506634652299f + (2.44506634652299f + x2) * fabsf(x + 0.814642734961073f * x * ax)));
}
static inline float act(int a, float x) {
    switch (a) {
        case ACT_RELU: return x > 0.f ? x : 0.f;
        case ACT_HARDTANH: return x < -1.f ? -1.f : (x > 1.f ? 1.f : x);
        case ACT_SIGMOID: return sigm(x);
        case ACT_LEAKY: return x > 0.f ? x : 0.01f * x;
        case ACT_FASTTANH: return fast_tanh(x);
        default: return tanhf(x);
    }
}

/* ------------------------------------------------------------------ WaveNet */

typedef struct {
    int dil;
    const float *conv;     /* [K][out][in] (re-ordered at load), out = ch * (gated ? 2 : 1) */
    const float *conv_b;   /* [out] */
    const float *mix;      /* [out][cond] */
    const float *w1;       /* [ch][ch] */
    const float *b1;       /* [ch] */
    float *ring;           /* history of this layer's input: [len][ch] */
    int len, mask, pos;
} Layer;

typedef struct {
    int in, cond, head, ch, k, gated, head_bias, n;
    const float *rech;     /* [ch][in] */
    Layer *layers;
    const float *head_w;   /* [head][ch] */
    const float *head_b;   /* [head] or NULL */
} Array;

typedef struct {
    int arch;              /* 0 WaveNet, 1 LSTM */
    int activation;
    int n_arrays;
    Array *arrays;
    float head_scale;
    float *w;              /* owned weights (re-ordered copy) */
    /* scratch */
    float *h, *z, *a, *head, *head_out, *prev_out, *prev_head;
    int max_ch;
    /* LSTM */
    int n_layers, in_size, hidden;
    float **lw, **lb, **xh, **c;   /* per layer: W [4H][I+H], b [4H], xh [I+H], c [H] */
    float **xh0, **c0;             /* the initial state, for reset */
    float *ifgo, *head_lw; float head_lb;
} Nam;

static int pow2(int v) { int p = 1; while (p < v) p <<= 1; return p; }

static void wavenet_reset(Nam *m) {
    for (int a = 0; a < m->n_arrays; a++) {
        Array *ar = &m->arrays[a];
        for (int l = 0; l < ar->n; l++) { memset(ar->layers[l].ring, 0, sizeof(float) * ar->layers[l].len * ar->ch); ar->layers[l].pos = 0; }
    }
}

static float wavenet_sample(Nam *m, float x) {
    const float cond = x;
    float *in = m->prev_out; int in_n = 1; in[0] = x;
    for (int a = 0; a < m->n_arrays; a++) {
        Array *ar = &m->arrays[a];
        const int ch = ar->ch, K = ar->k, outc = ch * (ar->gated ? 2 : 1);
        float *h = m->h, *z = m->z, *av = m->a, *head = m->head;
        /* into the array's channels */
        for (int c = 0; c < ch; c++) { float s = 0.f; const float *r = ar->rech + c * ar->in; for (int j = 0; j < in_n; j++) s += r[j] * in[j]; h[c] = s; }
        /* the head starts from the previous array's head */
        if (a == 0) memset(head, 0, sizeof(float) * ch); else memcpy(head, m->prev_head, sizeof(float) * ch);
        for (int l = 0; l < ar->n; l++) {
            Layer *L = &ar->layers[l];
            float *slot = L->ring + L->pos * ch;
            memcpy(slot, h, sizeof(float) * ch);
            for (int o = 0; o < outc; o++) z[o] = L->conv_b[o] + L->mix[o] * cond;  /* condition is one channel */
            for (int k = 0; k < K; k++) {
                const int back = (K - 1 - k) * L->dil;
                const float *xv = L->ring + ((L->pos - back) & L->mask) * ch;
                const float *wk = L->conv + (size_t)k * outc * ch;
                for (int o = 0; o < outc; o++) {
                    const float *wr = wk + o * ch; float s = 0.f;
                    for (int j = 0; j < ch; j++) s += wr[j] * xv[j];
                    z[o] += s;
                }
            }
            L->pos = (L->pos + 1) & L->mask;
            if (ar->gated) for (int c = 0; c < ch; c++) av[c] = act(m->activation, z[c]) * sigm(z[c + ch]);
            else for (int c = 0; c < ch; c++) av[c] = act(m->activation, z[c]);
            for (int c = 0; c < ch; c++) head[c] += av[c];
            for (int c = 0; c < ch; c++) { const float *wr = L->w1 + c * ch; float s = L->b1[c]; for (int j = 0; j < ch; j++) s += wr[j] * av[j]; h[c] += s; }
        }
        /* the head, and this array's output for the next */
        for (int o = 0; o < ar->head; o++) {
            const float *wr = ar->head_w + o * ch; float s = ar->head_b ? ar->head_b[o] : 0.f;
            for (int j = 0; j < ch; j++) s += wr[j] * head[j];
            m->head_out[o] = s;
        }
        memcpy(m->prev_head, m->head_out, sizeof(float) * ar->head);
        memcpy(m->prev_out, h, sizeof(float) * ch); in = m->prev_out; in_n = ch;
    }
    return m->head_scale * m->prev_head[0];
}

/* cfg: [0, activation, n_arrays, then per array: in, cond, head, ch, k, gated, head_bias, n, dil...] */
static Nam *wavenet_load(const int *cfg, int ncfg, const float *w, int nw) {
    Nam *m = calloc(1, sizeof(Nam));
    m->arch = 0; m->activation = cfg[1]; m->n_arrays = cfg[2];
    m->arrays = calloc(m->n_arrays, sizeof(Array));
    m->w = malloc(sizeof(float) * nw);
    int ci = 3; const float *src = w; float *dst = m->w; int max_ch = 1;
    for (int a = 0; a < m->n_arrays; a++) {
        Array *ar = &m->arrays[a];
        if (ci + 8 > ncfg) goto bad;
        ar->in = cfg[ci++]; ar->cond = cfg[ci++]; ar->head = cfg[ci++]; ar->ch = cfg[ci++]; ar->k = cfg[ci++];
        ar->gated = cfg[ci++]; ar->head_bias = cfg[ci++]; ar->n = cfg[ci++];
        if (ar->cond != 1 || ar->ch <= 0 || ar->ch > 64 || ar->k <= 0 || ar->k > 8 || ar->n <= 0 || ar->n > 64) goto bad;
        const int ch = ar->ch, K = ar->k, outc = ch * (ar->gated ? 2 : 1);
        if (ch > max_ch) max_ch = ch; if (ar->head > max_ch) max_ch = ar->head; if (outc > max_ch) max_ch = outc;
        ar->layers = calloc(ar->n, sizeof(Layer));
        /* rechannel: [ch][in], no bias */
        ar->rech = dst; for (int i = 0; i < ch * ar->in; i++) *dst++ = *src++;
        for (int l = 0; l < ar->n; l++) {
            Layer *L = &ar->layers[l];
            if (ci >= ncfg) goto bad;
            L->dil = cfg[ci++];
            /* conv: the file has [out][in][k]; keep it as [k][out][in] */
            L->conv = dst;
            for (int o = 0; o < outc; o++) for (int j = 0; j < ch; j++) for (int k = 0; k < K; k++) dst[(size_t)k * outc * ch + o * ch + j] = *src++;
            dst += (size_t)K * outc * ch;
            L->conv_b = dst; for (int o = 0; o < outc; o++) *dst++ = *src++;
            L->mix = dst; for (int o = 0; o < outc; o++) *dst++ = *src++;
            L->w1 = dst; for (int i = 0; i < ch * ch; i++) *dst++ = *src++;
            L->b1 = dst; for (int i = 0; i < ch; i++) *dst++ = *src++;
            L->len = pow2((K - 1) * L->dil + 1); L->mask = L->len - 1;
            L->ring = calloc((size_t)L->len * ch, sizeof(float));
        }
        ar->head_w = dst; for (int i = 0; i < ar->head * ch; i++) *dst++ = *src++;
        if (ar->head_bias) { ar->head_b = dst; for (int i = 0; i < ar->head; i++) *dst++ = *src++; } else ar->head_b = NULL;
        if (src - w > nw) goto bad;
    }
    if (src - w + 1 != nw) goto bad;
    m->head_scale = *src;
    m->max_ch = max_ch;
    m->h = calloc(max_ch, 4); m->z = calloc(max_ch * 2, 4); m->a = calloc(max_ch, 4); m->head = calloc(max_ch, 4);
    m->head_out = calloc(max_ch, 4); m->prev_out = calloc(max_ch, 4); m->prev_head = calloc(max_ch, 4);
    return m;
bad:
    /* leaks a little on a malformed file; the caller reports it */
    return NULL;
}

/* ------------------------------------------------------------------ LSTM */

static void lstm_reset(Nam *m) {
    for (int l = 0; l < m->n_layers; l++) {
        const int I = l == 0 ? m->in_size : m->hidden, H = m->hidden;
        memcpy(m->xh[l], m->xh0[l], sizeof(float) * (I + H)); memcpy(m->c[l], m->c0[l], sizeof(float) * H);
    }
}

static float lstm_sample(Nam *m, float x) {
    const int H = m->hidden;
    const float *in = &x; int in_n = 1;
    for (int l = 0; l < m->n_layers; l++) {
        const int I = l == 0 ? m->in_size : H;
        float *xh = m->xh[l], *c = m->c[l], *g = m->ifgo;
        for (int i = 0; i < I && i < in_n; i++) xh[i] = in[i];
        for (int r = 0; r < 4 * H; r++) { const float *wr = m->lw[l] + (size_t)r * (I + H); float s = m->lb[l][r]; for (int j = 0; j < I + H; j++) s += wr[j] * xh[j]; g[r] = s; }
        for (int i = 0; i < H; i++) c[i] = sigm(g[H + i]) * c[i] + sigm(g[i]) * tanhf(g[2 * H + i]);
        for (int i = 0; i < H; i++) xh[I + i] = sigm(g[3 * H + i]) * tanhf(c[i]);
        in = xh + I; in_n = H;
    }
    float s = m->head_lb;
    for (int i = 0; i < H; i++) s += m->head_lw[i] * in[i];
    return s;
}

/* cfg: [1, layers, input_size, hidden] */
static Nam *lstm_load(const int *cfg, int ncfg, const float *w, int nw) {
    if (ncfg < 4) return NULL;
    Nam *m = calloc(1, sizeof(Nam));
    m->arch = 1; m->n_layers = cfg[1]; m->in_size = cfg[2]; m->hidden = cfg[3];
    const int H = m->hidden, L = m->n_layers;
    if (L <= 0 || L > 8 || H <= 0 || H > 128 || m->in_size != 1) return NULL;
    m->lw = calloc(L, sizeof(float *)); m->lb = calloc(L, sizeof(float *)); m->xh = calloc(L, sizeof(float *)); m->c = calloc(L, sizeof(float *));
    m->xh0 = calloc(L, sizeof(float *)); m->c0 = calloc(L, sizeof(float *));
    const float *src = w;
    for (int l = 0; l < L; l++) {
        const int I = l == 0 ? m->in_size : H;
        m->lw[l] = malloc(sizeof(float) * 4 * H * (I + H)); memcpy(m->lw[l], src, sizeof(float) * 4 * H * (I + H)); src += 4 * H * (I + H);
        m->lb[l] = malloc(sizeof(float) * 4 * H); memcpy(m->lb[l], src, sizeof(float) * 4 * H); src += 4 * H;
        m->xh0[l] = calloc(I + H, 4); memcpy(m->xh0[l] + I, src, sizeof(float) * H); src += H;
        m->c0[l] = calloc(H, 4); memcpy(m->c0[l], src, sizeof(float) * H); src += H;
        m->xh[l] = calloc(I + H, 4); m->c[l] = calloc(H, 4);
    }
    m->head_lw = malloc(sizeof(float) * H); memcpy(m->head_lw, src, sizeof(float) * H); src += H;
    m->head_lb = *src++;
    if (src - w != nw) return NULL;
    m->ifgo = calloc(4 * H, 4);
    lstm_reset(m);
    return m;
}

/* ------------------------------------------------------------------ common */

static void nam_reset(Nam *m) { if (m->arch == 0) wavenet_reset(m); else lstm_reset(m); }

static void nam_process(Nam *m, float *buf, int n) {
    if (m->arch == 0) for (int i = 0; i < n; i++) buf[i] = wavenet_sample(m, buf[i]);
    else for (int i = 0; i < n; i++) buf[i] = lstm_sample(m, buf[i]);
}

static Nam *nam_load(const int *cfg, int ncfg, const float *w, int nw) {
    if (ncfg < 1) return NULL;
    Nam *m = cfg[0] == 0 ? wavenet_load(cfg, ncfg, w, nw) : cfg[0] == 1 ? lstm_load(cfg, ncfg, w, nw) : NULL;
    if (!m) return NULL;
    nam_reset(m);
    /* settle the model's initial state: half a second of silence */
    float z[256];
    for (int k = 0; k < 96; k++) { memset(z, 0, sizeof z); nam_process(m, z, 256); }
    return m;
}

static void nam_free(Nam *m) {
    if (!m) return;
    if (m->arch == 0) {
        for (int a = 0; a < m->n_arrays; a++) { for (int l = 0; l < m->arrays[a].n; l++) free(m->arrays[a].layers[l].ring); free(m->arrays[a].layers); }
        free(m->arrays); free(m->w);
        free(m->h); free(m->z); free(m->a); free(m->head); free(m->head_out); free(m->prev_out); free(m->prev_head);
    } else {
        for (int l = 0; l < m->n_layers; l++) { free(m->lw[l]); free(m->lb[l]); free(m->xh[l]); free(m->c[l]); free(m->xh0[l]); free(m->c0[l]); }
        free(m->lw); free(m->lb); free(m->xh); free(m->c); free(m->xh0); free(m->c0); free(m->ifgo); free(m->head_lw);
    }
    free(m);
}

/* ------------------------------------------------------------------ cabinet IR (direct FIR) */

typedef struct { int n, pos; float *h; float *x; } Ir;   /* x is doubled so a window is always contiguous */

static Ir *ir_load(const float *h, int n) {
    if (n <= 0) return NULL;
    Ir *r = calloc(1, sizeof(Ir));
    r->n = n; r->h = malloc(sizeof(float) * n); r->x = calloc((size_t)n * 2, sizeof(float));
    for (int i = 0; i < n; i++) r->h[i] = h[n - 1 - i];   /* reversed: a dot product with the latest n inputs */
    return r;
}

static void ir_process(Ir *r, float *buf, int n) {
    const int N = r->n;
    for (int i = 0; i < n; i++) {
        r->x[r->pos] = buf[i]; r->x[r->pos + N] = buf[i];
        r->pos = r->pos + 1 == N ? 0 : r->pos + 1;
        const float *xv = r->x + r->pos;   /* oldest .. newest */
        float s = 0.f;
        for (int k = 0; k < N; k++) s += r->h[k] * xv[k];
        buf[i] = s;
    }
}

static void ir_free(Ir *r) { if (!r) return; free(r->h); free(r->x); free(r); }

/* ------------------------------------------------------------------ JNI: app.foxbox.audio.NativeNam */

JNIEXPORT jlong JNICALL Java_app_foxbox_audio_NativeNam_nLoad(JNIEnv *env, jclass cls, jintArray cfg, jfloatArray w) {
    const int nc = (*env)->GetArrayLength(env, cfg), nw = (*env)->GetArrayLength(env, w);
    jint *c = (*env)->GetIntArrayElements(env, cfg, NULL);
    jfloat *f = (*env)->GetFloatArrayElements(env, w, NULL);
    Nam *m = nam_load((const int *)c, nc, f, nw);
    (*env)->ReleaseFloatArrayElements(env, w, f, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, cfg, c, JNI_ABORT);
    return (jlong)(intptr_t)m;
}

JNIEXPORT void JNICALL Java_app_foxbox_audio_NativeNam_nProcess(JNIEnv *env, jclass cls, jlong h, jfloatArray buf, jint n) {
    Nam *m = (Nam *)(intptr_t)h; if (!m) return;
    jfloat *b = (*env)->GetPrimitiveArrayCritical(env, buf, NULL);
    nam_process(m, b, n);
    (*env)->ReleasePrimitiveArrayCritical(env, buf, b, 0);
}

JNIEXPORT void JNICALL Java_app_foxbox_audio_NativeNam_nReset(JNIEnv *env, jclass cls, jlong h) { if (h) nam_reset((Nam *)(intptr_t)h); }
JNIEXPORT void JNICALL Java_app_foxbox_audio_NativeNam_nFree(JNIEnv *env, jclass cls, jlong h) { nam_free((Nam *)(intptr_t)h); }

JNIEXPORT jlong JNICALL Java_app_foxbox_audio_NativeNam_nIrLoad(JNIEnv *env, jclass cls, jfloatArray ir) {
    const int n = (*env)->GetArrayLength(env, ir);
    jfloat *f = (*env)->GetFloatArrayElements(env, ir, NULL);
    Ir *r = ir_load(f, n);
    (*env)->ReleaseFloatArrayElements(env, ir, f, JNI_ABORT);
    return (jlong)(intptr_t)r;
}

JNIEXPORT void JNICALL Java_app_foxbox_audio_NativeNam_nIrProcess(JNIEnv *env, jclass cls, jlong h, jfloatArray buf, jint n) {
    Ir *r = (Ir *)(intptr_t)h; if (!r) return;
    jfloat *b = (*env)->GetPrimitiveArrayCritical(env, buf, NULL);
    ir_process(r, b, n);
    (*env)->ReleasePrimitiveArrayCritical(env, buf, b, 0);
}

JNIEXPORT void JNICALL Java_app_foxbox_audio_NativeNam_nIrFree(JNIEnv *env, jclass cls, jlong h) { ir_free((Ir *)(intptr_t)h); }

#ifdef NAM_TEST
/* Offline check against the reference: nam_test cfg.bin weights.bin in.f32 out.f32 */
#include <stdio.h>
static void *slurp(const char *p, long *len) { FILE *f = fopen(p, "rb"); fseek(f, 0, SEEK_END); *len = ftell(f); fseek(f, 0, SEEK_SET); void *b = malloc(*len); fread(b, 1, *len, f); fclose(f); return b; }
int main(int argc, char **argv) {
    long lc, lw, li; int *cfg = slurp(argv[1], &lc); float *w = slurp(argv[2], &lw); float *in = slurp(argv[3], &li);
    Nam *m = nam_load(cfg, (int)(lc / 4), w, (int)(lw / 4));
    if (!m) { fprintf(stderr, "load failed\n"); return 1; }

    for (long i = 0; i < li / 4; i += 64) nam_process(m, in + i, (int)((li / 4 - i) < 64 ? (li / 4 - i) : 64));
    FILE *o = fopen(argv[4], "wb"); fwrite(in, 1, li, o); fclose(o);
    return 0;
}
#endif
