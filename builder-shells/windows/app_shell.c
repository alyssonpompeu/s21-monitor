#define UNICODE
#define _UNICODE
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_SPEC (256 * 1024)
#define MAX_FIELDS 8
#define MAX_CONTROLS 64
#define MAX_BUTTONS 32
#define MARKER "\nIAPAYLOAD1\n"

typedef struct { char id[32]; HWND hwnd; } Field;
typedef struct { HWND hwnd; char action[24]; char a[256]; char b[256]; } ButtonAction;

static Field g_fields[MAX_CONTROLS];
static int g_field_count = 0;
static ButtonAction g_buttons[MAX_BUTTONS];
static int g_button_count = 0;
static HFONT g_font = NULL;
static HBRUSH g_bg = NULL;
static int g_dark = 0;

static wchar_t *utf8_to_wide(const char *s) {
    if (!s) s = "";
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 0) return NULL;
    wchar_t *w = (wchar_t*)calloc((size_t)n, sizeof(wchar_t));
    if (!w) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n);
    return w;
}

static HWND field_find(const char *id) {
    for (int i = 0; i < g_field_count; ++i) if (strcmp(g_fields[i].id, id) == 0) return g_fields[i].hwnd;
    return NULL;
}

static void field_add(const char *id, HWND hwnd) {
    if (!id || !*id || !hwnd || g_field_count >= MAX_CONTROLS) return;
    strncpy(g_fields[g_field_count].id, id, sizeof(g_fields[g_field_count].id) - 1);
    g_fields[g_field_count].id[sizeof(g_fields[g_field_count].id) - 1] = 0;
    g_fields[g_field_count].hwnd = hwnd;
    g_field_count++;
}

static void get_text(HWND hwnd, wchar_t *buf, int cap) {
    if (!buf || cap <= 0) return;
    buf[0] = 0;
    if (hwnd) GetWindowTextW(hwnd, buf, cap);
}

static void ini_path(wchar_t *buf, int cap) {
    GetModuleFileNameW(NULL, buf, cap);
    wchar_t *dot = wcsrchr(buf, L'.');
    if (dot) wcscpy(dot, L".ini"); else wcscat(buf, L".ini");
}

static void execute_button(ButtonAction *ba, HWND parent) {
    if (!ba) return;
    if (_stricmp(ba->action, "COPY") == 0) {
        HWND src = field_find(ba->a), dst = field_find(ba->b);
        wchar_t text[4096]; get_text(src, text, 4096); if (dst) SetWindowTextW(dst, text);
    } else if (_stricmp(ba->action, "SUM") == 0) {
        char ids[256]; strncpy(ids, ba->a, sizeof(ids)-1); ids[sizeof(ids)-1] = 0;
        double total = 0.0; char *ctx = NULL; char *tok = strtok_s(ids, ",", &ctx);
        while (tok) { wchar_t text[256]; get_text(field_find(tok), text, 256); total += _wtof(text); tok = strtok_s(NULL, ",", &ctx); }
        wchar_t out[128]; swprintf(out, 128, L"%.10g", total); HWND dst = field_find(ba->b); if (dst) SetWindowTextW(dst, out);
    } else if (_stricmp(ba->action, "CLEAR") == 0) {
        char ids[256]; strncpy(ids, ba->a, sizeof(ids)-1); ids[sizeof(ids)-1] = 0;
        char *ctx = NULL; char *tok = strtok_s(ids, ",", &ctx); while (tok) { HWND h = field_find(tok); if (h) SetWindowTextW(h, L""); tok = strtok_s(NULL, ",", &ctx); }
    } else if (_stricmp(ba->action, "TOAST") == 0) {
        wchar_t *msg = utf8_to_wide(ba->a); if (msg) { MessageBoxW(parent, msg, L"IA App", MB_OK | MB_ICONINFORMATION); free(msg); }
    } else if (_stricmp(ba->action, "SAVE") == 0) {
        wchar_t *key = utf8_to_wide(ba->a); wchar_t value[4096]; get_text(field_find(ba->b), value, 4096); wchar_t path[MAX_PATH]; ini_path(path, MAX_PATH);
        if (key) { WritePrivateProfileStringW(L"IAApp", key, value, path); free(key); }
    } else if (_stricmp(ba->action, "LOAD") == 0) {
        wchar_t *key = utf8_to_wide(ba->a); wchar_t value[4096] = L""; wchar_t path[MAX_PATH]; ini_path(path, MAX_PATH);
        if (key) { GetPrivateProfileStringW(L"IAApp", key, L"", value, 4096, path); HWND dst = field_find(ba->b); if (dst) SetWindowTextW(dst, value); free(key); }
    }
}

static char *load_payload(void) {
    wchar_t path[MAX_PATH]; GetModuleFileNameW(NULL, path, MAX_PATH);
    FILE *f = _wfopen(path, L"rb"); if (!f) return NULL;
    _fseeki64(f, 0, SEEK_END); long long size = _ftelli64(f);
    long long read_size = size < (MAX_SPEC + 128) ? size : (MAX_SPEC + 128);
    _fseeki64(f, size - read_size, SEEK_SET);
    char *tail = (char*)malloc((size_t)read_size + 1); if (!tail) { fclose(f); return NULL; }
    size_t got = fread(tail, 1, (size_t)read_size, f); fclose(f); tail[got] = 0;
    char *found = NULL; char *p = tail;
    while ((p = strstr(p, MARKER)) != NULL) { found = p; p += 1; }
    if (!found) { free(tail); return NULL; }
    found += strlen(MARKER);
    size_t len = strlen(found); if (len > MAX_SPEC) { free(tail); return NULL; }
    char *spec = _strdup(found); free(tail); return spec;
}

static void parse_title(const char *spec, wchar_t *out, int cap) {
    wcsncpy(out, L"IA App", cap - 1); out[cap - 1] = 0;
    if (!spec) return;
    const char *p = strstr(spec, "APP|"); if (!p) return; p += 4;
    const char *e = strpbrk(p, "\r\n"); size_t len = e ? (size_t)(e - p) : strlen(p); if (len > 160) len = 160;
    char tmp[164]; memcpy(tmp, p, len); tmp[len] = 0; wchar_t *w = utf8_to_wide(tmp); if (w) { wcsncpy(out, w, cap - 1); out[cap - 1] = 0; free(w); }
}

static HWND make_control(HWND parent, const wchar_t *klass, const wchar_t *text, DWORD style, int x, int y, int w, int h) {
    HWND c = CreateWindowExW(0, klass, text, WS_CHILD | WS_VISIBLE | style, x, y, w, h, parent, NULL, GetModuleHandleW(NULL), NULL);
    if (c && g_font) SendMessageW(c, WM_SETFONT, (WPARAM)g_font, TRUE);
    return c;
}

static void build_controls(HWND hwnd, const char *spec) {
    if (!spec) { make_control(hwnd, L"STATIC", L"Nenhuma interface foi anexada a este executável.", SS_LEFT, 24, 30, 700, 30); return; }
    char *copy = _strdup(spec); if (!copy) return;
    int y = 24; char *line_ctx = NULL; char *line = strtok_s(copy, "\r\n", &line_ctx);
    while (line && y < 900) {
        char local[4096]; strncpy(local, line, sizeof(local)-1); local[sizeof(local)-1] = 0;
        char *parts[MAX_FIELDS] = {0}; int n = 0; char *field_ctx = NULL; char *tok = strtok_s(local, "|", &field_ctx);
        while (tok && n < MAX_FIELDS) { parts[n++] = tok; tok = strtok_s(NULL, "|", &field_ctx); }
        if (n > 1 && _stricmp(parts[0], "THEME") == 0) g_dark = _stricmp(parts[1], "dark") == 0;
        else if (n > 1 && _stricmp(parts[0], "TEXT") == 0) {
            wchar_t *w = utf8_to_wide(parts[1]); if (w) { make_control(hwnd, L"STATIC", w, SS_LEFT, 24, y, 720, 32); free(w); } y += 40;
        } else if (n > 2 && _stricmp(parts[0], "INPUT") == 0) {
            HWND e = make_control(hwnd, L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL | WS_TABSTOP, 24, y, 720, 34); field_add(parts[1], e);
            wchar_t *hint = utf8_to_wide(parts[2]); if (hint) { SendMessageW(e, 0x1501, TRUE, (LPARAM)hint); free(hint); } y += 44;
        } else if (n > 2 && _stricmp(parts[0], "RESULT") == 0) {
            wchar_t *w = utf8_to_wide(parts[2]); HWND e = make_control(hwnd, L"EDIT", w ? w : L"", WS_BORDER | ES_READONLY, 24, y, 720, 34); if (w) free(w); field_add(parts[1], e); y += 44;
        } else if (n > 2 && _stricmp(parts[0], "CHECK") == 0) {
            wchar_t *w = utf8_to_wide(parts[2]); HWND c = make_control(hwnd, L"BUTTON", w ? w : L"", BS_AUTOCHECKBOX | WS_TABSTOP, 24, y, 720, 30); if (w) free(w); field_add(parts[1], c); y += 38;
        } else if (n > 1 && _stricmp(parts[0], "SPACE") == 0) {
            int gap = atoi(parts[1]); if (gap < 4) gap = 4; if (gap > 80) gap = 80; y += gap;
        } else if (n > 2 && _stricmp(parts[0], "BUTTON") == 0 && g_button_count < MAX_BUTTONS) {
            wchar_t *w = utf8_to_wide(parts[1]); HWND b = make_control(hwnd, L"BUTTON", w ? w : L"Ação", BS_PUSHBUTTON | WS_TABSTOP, 24, y, 720, 38); if (w) free(w);
            ButtonAction *ba = &g_buttons[g_button_count++]; ba->hwnd = b; strncpy(ba->action, parts[2], sizeof(ba->action)-1); ba->action[sizeof(ba->action)-1] = 0;
            if (n > 3) { strncpy(ba->a, parts[3], sizeof(ba->a)-1); ba->a[sizeof(ba->a)-1] = 0; }
            if (n > 4) { strncpy(ba->b, parts[4], sizeof(ba->b)-1); ba->b[sizeof(ba->b)-1] = 0; }
            y += 48;
        }
        line = strtok_s(NULL, "\r\n", &line_ctx);
    }
    free(copy);
}

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_COMMAND: {
            HWND source = (HWND)lp;
            if (HIWORD(wp) == BN_CLICKED) for (int i = 0; i < g_button_count; ++i) if (g_buttons[i].hwnd == source) { execute_button(&g_buttons[i], hwnd); return 0; }
            break;
        }
        case WM_CTLCOLORSTATIC:
        case WM_CTLCOLOREDIT:
            if (g_dark) { SetTextColor((HDC)wp, RGB(235,235,235)); SetBkColor((HDC)wp, RGB(32,33,36)); return (LRESULT)g_bg; }
            break;
        case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show) {
    (void)prev; (void)cmd;
    char *spec = load_payload(); wchar_t title[192]; parse_title(spec, title, 192);
    g_font = CreateFontW(-18,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_DONTCARE,L"Segoe UI");
    g_bg = CreateSolidBrush(RGB(32,33,36));
    WNDCLASSW wc = {0}; wc.lpfnWndProc = wndproc; wc.hInstance = inst; wc.lpszClassName = L"IAOfflineGeneratedApp"; wc.hCursor = LoadCursor(NULL, IDC_ARROW); wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    RegisterClassW(&wc);
    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, title, WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 800, 720, NULL, NULL, inst, NULL);
    if (!hwnd) { free(spec); return 1; }
    build_controls(hwnd, spec); free(spec);
    if (g_dark) { SetClassLongPtrW(hwnd, GCLP_HBRBACKGROUND, (LONG_PTR)g_bg); InvalidateRect(hwnd, NULL, TRUE); }
    ShowWindow(hwnd, show); UpdateWindow(hwnd);
    MSG msg; while (GetMessageW(&msg, NULL, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessageW(&msg); }
    if (g_font) DeleteObject(g_font); if (g_bg) DeleteObject(g_bg);
    return (int)msg.wParam;
}
