/* Android menu bridge for the already bundled BRF 70.648 module.
 * Only this module's menu/config API is replaced, never gameplay functions.
 * Configuration is applied on its original render thread, outside the mutex.
 */
#include <glib.h>
#include <stdint.h>
#include <string.h>

extern void iris_apply(void *context, unsigned char *config);
typedef struct {
    GMutex lock;
    int pending[88];
    unsigned char config[88];
    int frame_count, frame_rate, is_ready, error, progress, interactive;
} IrisState;
/* CModule data pages are read-only; mutable storage is provided by the host. */
extern IrisState iris_state;
#define mutex iris_state.lock
#define requested iris_state.pending
#define observed iris_state.config
#define frames iris_state.frame_count
#define fps iris_state.frame_rate
#define ready iris_state.is_ready
#define last_error iris_state.error
#define loading_progress iris_state.progress
#define initialized iris_state.interactive

static int boolean_slot(int slot) {
    switch (slot) {
        case 1: case 2: case 4: case 5: case 9: case 14: case 16:
        case 17: case 18: case 19: case 25: case 26: case 48:
        case 49: case 50: case 51: case 52: case 53: case 54: case 55:
            return 1;
        default: return 0;
    }
}

int submit(int slot, int value) {
    if (boolean_slot(slot)) { if (value < 0 || value > 1) return 0; }
    else if (slot == 56) { if (value < 0 || value > 100) return 0; }
    else if (slot == 60) { if (value < 0 || value > 1000) return 0; }
    else if (slot == 64) { if (value < 10 || value > 121) return 0; }
    else return 0; /* No farm, duplicate auto-goal, unknown offset or menu. */
    g_mutex_lock(&mutex);
    requested[slot] = value + 1;
    g_mutex_unlock(&mutex);
    return 1;
}

void render(void *context, void *renderer, float width, float height,
            int progress, int current_fps, int interactive) {
    unsigned char *ctx = context, config[88];
    int changes[88], i, changed;
    if (!ctx) return;
    memcpy(config, ctx + 0x68, 88);
    /* BRF passes interactive = (its initialization state == 3). The context
     * API pointer is created earlier, while its worker is still installing
     * hooks. Do not call that API or report readiness until initialization
     * has completed. These are module progress values, not server login. */
    if (!interactive || progress < 100 || !*(uintptr_t *)(ctx + 0xe0)) {
        g_mutex_lock(&mutex);
        memcpy(observed, config, 88);
        fps = current_fps;
        ready = 0;
        loading_progress = progress;
        initialized = interactive != 0;
        frames++;
        g_mutex_unlock(&mutex);
        return; /* Keep requested settings queued for the first ready frame. */
    }
    g_mutex_lock(&mutex);
    memcpy(changes, requested, sizeof(changes));
    memset(requested, 0, sizeof(requested));
    g_mutex_unlock(&mutex);
    changed = config[3] != 0 || config[11] != 0;
    /* Iris owns both farm and licensed goal. Do not start duplicate BRF bots. */
    config[3] = 0; config[11] = 0;
    for (i = 0; i < 88; i++) {
        if (!changes[i]) continue;
        if (boolean_slot(i)) config[i] = (unsigned char)(changes[i] - 1);
        else { int value = changes[i] - 1; memcpy(config + i, &value, 4); }
        changed = 1;
    }
    if (changed) iris_apply(context, config);
    /* Remove original native menu hit regions. The Iris Android panel owns UI. */
    if (*(uintptr_t *)(ctx + 0x130) != *(uintptr_t *)(ctx + 0x128)) {
        *(uintptr_t *)(ctx + 0x130) = *(uintptr_t *)(ctx + 0x128);
        (*(uint64_t *)(ctx + 0x198))++;
    }
    ctx[0x174] = 0;
    g_mutex_lock(&mutex);
    memcpy(observed, ctx + 0x68, 88);
    if (changed) {
        last_error = 0;
        for (i = 0; i < 88; i++) if (config[i] != observed[i]) last_error = 1;
    }
    fps = current_fps;
    ready = 1;
    loading_progress = progress;
    initialized = 1;
    frames++;
    g_mutex_unlock(&mutex);
}

/* Flat snapshot: [ready, frames, fps, error, config[88], progress, interactive]. */
void snapshot(void *output) {
    int *header = output;
    g_mutex_lock(&mutex);
    header[0] = ready; header[1] = frames; header[2] = fps; header[3] = last_error;
    memcpy((unsigned char *)output + 16, observed, 88);
    header[26] = loading_progress;
    header[27] = initialized;
    g_mutex_unlock(&mutex);
}
