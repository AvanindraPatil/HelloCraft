// hn_input.cpp - routes Hello Neighbor's keyboard/mouse to the hidden Minecraft (step 3 part 1).
//
// The game window's procedure is subclassed, so we see WM_KEY*, mouse buttons, the wheel and raw mouse input
// before Unreal does. Events meant for Minecraft go into the overlay mapping's input ring (protocol/hn_overlay.h)
// and are swallowed; everything else goes on to the game.
//
// Routing (only while Minecraft is alive, i.e. still publishing overlay frames):
//   no Minecraft screen open: mouse buttons, wheel, 1-9, E, Q, F -> Minecraft; everything else -> Hello Neighbor
//   a Minecraft screen open (inventory, chat): all keys, text, buttons, wheel and the mouse (as a cursor) ->
//     Minecraft; Hello Neighbor's camera does not move
// A key-up always goes where its key-down went, so nothing sticks when a screen opens or closes.
// Keys are positional: keyboard scancodes (set 1) are mapped to SDL scancodes (= USB HID usages), which is
// what Minecraft 26 uses, so WASD-style bindings work on any keyboard layout.
#include "hn_input.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "../../protocol/hn_overlay.h"

namespace hninput {
namespace {

WNDPROC g_orig = nullptr;
HWND g_hwnd = nullptr;
uint8_t* g_base = nullptr;
LogFn g_log = nullptr;
std::atomic<bool> g_alive{false};
std::atomic<int> g_vpW{1920}, g_vpH{1080};
std::atomic<int> g_curX{0}, g_curY{0};
std::atomic<bool> g_cursorVisible{false};
bool g_wasScreenOpen = false;
bool g_wasHostMenu = false;
bool g_routedSinceAlive = false;

// Where each key-down went (index = SDL scancode): 0 = nowhere, 1 = Minecraft, 2 = the game.
uint8_t g_keyOwner[256];
bool g_buttonDown[8];
unsigned long long g_pushed = 0, g_dropped = 0;

// Keyboard scancode set 1 -> SDL scancode / USB HID usage. [ext][code]; 0 = unmapped.
uint16_t g_set1[2][128];

void initTable() {
    uint16_t (&n)[128] = g_set1[0];
    uint16_t (&e)[128] = g_set1[1];
    n[0x01] = 41;                                                   // Esc
    for (int i = 0; i < 9; ++i) n[0x02 + i] = (uint16_t)(30 + i);  // 1..9
    n[0x0B] = 39;                                                   // 0
    n[0x0C] = 45; n[0x0D] = 46; n[0x0E] = 42; n[0x0F] = 43;        // - = Backspace Tab
    const uint16_t qrow[] = { 20, 26, 8, 21, 23, 28, 24, 12, 18, 19, 47, 48 };   // Q W E R T Y U I O P [ ]
    for (int i = 0; i < 12; ++i) n[0x10 + i] = qrow[i];
    n[0x1C] = 40; e[0x1C] = 88;                                     // Enter, keypad Enter
    n[0x1D] = 224; e[0x1D] = 228;                                   // LCtrl, RCtrl
    const uint16_t arow[] = { 4, 22, 7, 9, 10, 11, 13, 14, 15, 51, 52, 53 };     // A S D F G H J K L ; ' `
    for (int i = 0; i < 12; ++i) n[0x1E + i] = arow[i];
    n[0x2A] = 225; n[0x2B] = 49;                                    // LShift, backslash
    const uint16_t zrow[] = { 29, 27, 6, 25, 5, 17, 16, 54, 55, 56 };            // Z X C V B N M , . /
    for (int i = 0; i < 10; ++i) n[0x2C + i] = zrow[i];
    e[0x35] = 84;                                                   // keypad /
    n[0x36] = 229;                                                  // RShift
    n[0x37] = 85; e[0x37] = 70;                                     // keypad *, PrintScreen
    n[0x38] = 226; e[0x38] = 230;                                   // LAlt, RAlt
    n[0x39] = 44; n[0x3A] = 57;                                     // Space, CapsLock
    for (int i = 0; i < 10; ++i) n[0x3B + i] = (uint16_t)(58 + i);  // F1..F10
    n[0x45] = 83; n[0x46] = 71;                                     // NumLock, ScrollLock
    const uint16_t pad[] = { 95, 96, 97, 86, 92, 93, 94, 87, 89, 90, 91, 98, 99 }; // KP7 8 9 - 4 5 6 + 1 2 3 0 .
    for (int i = 0; i < 13; ++i) n[0x47 + i] = pad[i];
    e[0x47] = 74; e[0x48] = 82; e[0x49] = 75; e[0x4B] = 80; e[0x4D] = 79;       // Home Up PgUp Left Right
    e[0x4F] = 77; e[0x50] = 81; e[0x51] = 78; e[0x52] = 73; e[0x53] = 76;       // End Down PgDn Insert Delete
    n[0x57] = 68; n[0x58] = 69;                                     // F11 F12
    e[0x5B] = 227; e[0x5C] = 231; e[0x5D] = 101;                    // LGUI RGUI Menu
}

bool screenOpen() {
    return (std::atomic_ref<uint32_t>(((hn::OverlayHeader*)g_base)->mcFlags).load(std::memory_order_acquire) & hn::kOverlayMcScreenOpen) != 0;
}

bool moveKeysToMinecraft() {
    return (std::atomic_ref<uint32_t>(((hn::OverlayHeader*)g_base)->mcFlags).load(std::memory_order_acquire) & hn::kOverlayMcMoveKeys) != 0;
}

// Keys Hello Neighbor always keeps: Esc (its menu), ` (console), F4 and F6-F12 (the mod's keys, UE4SS console).
bool hostKey(int hid) { return hid == 41 || hid == 53 || hid == 61 || (hid >= 63 && hid <= 69); }

// Keys Minecraft gets even when no Minecraft screen is open: hotbar 1-9, E (inventory), Q (drop), F (swap hands).
// While Minecraft moves the player (kOverlayMcMoveKeys) it gets EVERY key except hostKey(): W A S D, Space, Shift,
// Ctrl, T (chat), / (commands), Tab, F1 (hide HUD), F3 (debug), F5 (camera), ... exactly like Minecraft.
bool minecraftKey(int hid) {
    if ((hid >= 30 && hid <= 38) || hid == 8 || hid == 20 || hid == 9) return true;
    return moveKeysToMinecraft() && !hostKey(hid);
}

// Space goes to BOTH games (unless a Minecraft screen is open, e.g. typing in chat): Minecraft jumps, and Hello
// Neighbor gets it for its own "mash Space" moments (escaping the neighbour's grip in Act 3, the ability that pushes
// him away when he catches you). Its own jump does nothing meanwhile: its movement is off while Minecraft moves.
// F goes to both as well, for Hello Neighbor mods that use it (the Animatronics mods); Minecraft swaps hands.
constexpr int kHidSpace = 44, kHidF = 9;
bool mirrorToGame(int hid, bool screen) { return (hid == kHidSpace || hid == kHidF) && !screen; }

void push(uint16_t type, uint16_t code, int a, int b = 0, int c = 0) {
    hn::InputEvent ev{ type, code, a, b, c };
    if (hn::inputPush(g_base, ev)) ++g_pushed; else ++g_dropped;
    g_routedSinceAlive = true;
}

void releaseAll() {
    push(hn::kInReleaseAll, 0, 0);
    for (int i = 0; i < 256; ++i) if (g_keyOwner[i] == 1) g_keyOwner[i] = 0;
    std::memset(g_buttonDown, 0, sizeof(g_buttonDown));
}

LRESULT button(UINT msg, int sdlButton, bool down) {
    (void)msg;
    if (sdlButton > 0 && sdlButton < 8) g_buttonDown[sdlButton] = down;
    push(hn::kInMouseButton, (uint16_t)sdlButton, down ? 1 : 0);
    return 0;
}

// ---- Taps: Minecraft asks for one of Hello Neighbor's own inputs (hostTaps in the overlay header). ----
// The pump (render thread, every frame) posts WM_HN_TAP to the window; proc() turns it into the real key or button
// message and hands it STRAIGHT to the game's window procedure, so the router never sends it to Minecraft. The
// release follows when Minecraft's key is let go (slot keys: a few frames later, a short press).
constexpr UINT WM_HN_TAP = WM_APP + 0x48;   // wParam = tap kind (kTap*), lParam = slot (kTapSlot) | 0x100 for release
constexpr int kTapHoldFrames = 4;
uint32_t g_tapSeen[4];
bool g_tapInit = false;
int g_tapQueue[16][2];                       // pending presses: { kind, slot }
int g_tapQueued = 0;
int g_tapActive[2] = { -1, 0 }, g_tapFrames = 0;
unsigned long long g_taps = 0;
bool g_holdDown[3];                          // use / apply / throw key currently down in Hello Neighbor
int g_holdFrames[3];

// The key each tap presses = the player's CURRENT Hello Neighbor binding (it can be rebound, e.g. pick up E -> R).
// HnLink writes them to hn_keys.txt as Unreal key names ("use R", "apply LeftMouseButton", "throw RightMouseButton");
// re-read every couple of seconds. Until then: Hello Neighbor's defaults.
struct TapKey { UINT vk; int button; };     // button: 0 = keyboard key vk, 1 left, 2 right, 3 middle, 4/5 thumb
TapKey g_tapKey[3] = { { 'E', 0 }, { 0, 1 }, { 0, 2 } };
char g_keysFile[MAX_PATH];
char g_keysText[256];
int g_keysFrames = 0;

bool keyFromName(const char* n, TapKey& k) {
    k = { 0, 0 };
    if (n[0] && !n[1] && ((n[0] >= 'A' && n[0] <= 'Z') || (n[0] >= '0' && n[0] <= '9'))) { k.vk = (UINT)n[0]; return true; }
    static const struct { const char* name; UINT vk; int button; } kTable[] = {
        { "LeftMouseButton", 0, 1 }, { "RightMouseButton", 0, 2 }, { "MiddleMouseButton", 0, 3 },
        { "ThumbMouseButton", 0, 4 }, { "ThumbMouseButton2", 0, 5 },
        { "Zero", '0', 0 }, { "One", '1', 0 }, { "Two", '2', 0 }, { "Three", '3', 0 }, { "Four", '4', 0 }, { "Five", '5', 0 },
        { "Six", '6', 0 }, { "Seven", '7', 0 }, { "Eight", '8', 0 }, { "Nine", '9', 0 },
        { "SpaceBar", VK_SPACE, 0 }, { "Tab", VK_TAB, 0 }, { "Enter", VK_RETURN, 0 }, { "BackSpace", VK_BACK, 0 },
        { "LeftShift", VK_LSHIFT, 0 }, { "RightShift", VK_RSHIFT, 0 }, { "LeftControl", VK_LCONTROL, 0 }, { "RightControl", VK_RCONTROL, 0 },
        { "LeftAlt", VK_LMENU, 0 }, { "RightAlt", VK_RMENU, 0 }, { "CapsLock", VK_CAPITAL, 0 },
        { "Semicolon", VK_OEM_1, 0 }, { "Equals", VK_OEM_PLUS, 0 }, { "Comma", VK_OEM_COMMA, 0 }, { "Hyphen", VK_OEM_MINUS, 0 },
        { "Period", VK_OEM_PERIOD, 0 }, { "Slash", VK_OEM_2, 0 }, { "Tilde", VK_OEM_3, 0 }, { "LeftBracket", VK_OEM_4, 0 },
        { "Backslash", VK_OEM_5, 0 }, { "RightBracket", VK_OEM_6, 0 }, { "Apostrophe", VK_OEM_7, 0 },
        { "Insert", VK_INSERT, 0 }, { "Delete", VK_DELETE, 0 }, { "Home", VK_HOME, 0 }, { "End", VK_END, 0 },
        { "PageUp", VK_PRIOR, 0 }, { "PageDown", VK_NEXT, 0 }, { "Up", VK_UP, 0 }, { "Down", VK_DOWN, 0 }, { "Left", VK_LEFT, 0 }, { "Right", VK_RIGHT, 0 },
    };
    for (const auto& e : kTable) if (std::strcmp(n, e.name) == 0) { k.vk = e.vk; k.button = e.button; return true; }
    if (n[0] == 'F' && n[1] >= '1' && n[1] <= '9') { int f = std::atoi(n + 1); if (f >= 1 && f <= 12) { k.vk = VK_F1 + f - 1; return true; } }
    if (std::strncmp(n, "NumPad", 6) == 0) {
        static const char* kDigits[] = { "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine" };
        for (int i = 0; i < 10; ++i) if (std::strcmp(n + 6, kDigits[i]) == 0) { k.vk = VK_NUMPAD0 + i; return true; }
    }
    return false;
}

void loadTapKeys() {
    if (!g_keysFile[0]) {
        char exe[MAX_PATH];
        DWORD n = GetModuleFileNameA(nullptr, exe, MAX_PATH);
        if (!n || n >= MAX_PATH) return;
        char* slash = std::strrchr(exe, '\\');
        if (slash) slash[1] = 0;
        std::snprintf(g_keysFile, sizeof(g_keysFile), "%shn_keys.txt", exe);
    }
    FILE* f = std::fopen(g_keysFile, "r");
    if (!f) return;
    char text[256] = {};
    size_t len = std::fread(text, 1, sizeof(text) - 1, f);
    std::fclose(f);
    text[len] = 0;
    if (std::strcmp(text, g_keysText) == 0) return;
    std::memcpy(g_keysText, text, sizeof(text));
    static const char* kKinds[] = { "use", "apply", "throw" };
    char line[96];
    for (const char* p = text; *p;) {
        const char* e = std::strchr(p, '\n');
        size_t l = e ? (size_t)(e - p) : std::strlen(p);
        if (l < sizeof(line)) {
            std::memcpy(line, p, l);
            line[l] = 0;
            if (l && line[l - 1] == '\r') line[l - 1] = 0;
            char* sp = std::strchr(line, ' ');
            if (sp) {
                *sp = 0;
                for (int k = 0; k < 3; ++k) {
                    if (std::strcmp(line, kKinds[k]) != 0) continue;
                    TapKey t;
                    if (keyFromName(sp + 1, t)) { g_tapKey[k] = t; if (g_log) g_log("input: Hello Neighbor '%s' = %s", kKinds[k], sp + 1); }
                    else if (g_log) g_log("input: Hello Neighbor '%s' is bound to %s, which hn_gfx cannot press; keeping the old key", kKinds[k], sp + 1);
                }
            }
        }
        if (!e) break;
        p = e + 1;
    }
}

void deliverTap(HWND h, int kind, int slot, bool down) {
    UINT msg = 0; WPARAM wp = 0; LPARAM lp = 0;
    auto key = [&](UINT vk) {
        UINT sc = MapVirtualKeyW(vk, MAPVK_VK_TO_VSC);
        msg = down ? WM_KEYDOWN : WM_KEYUP;
        // Unreal reads plain VK_SHIFT/VK_CONTROL/VK_MENU and tells left from right by the scancode.
        wp = (vk == VK_LSHIFT || vk == VK_RSHIFT) ? VK_SHIFT : (vk == VK_LCONTROL || vk == VK_RCONTROL) ? VK_CONTROL : (vk == VK_LMENU || vk == VK_RMENU) ? VK_MENU : vk;
        bool ext = vk == VK_RCONTROL || vk == VK_RMENU || (vk >= VK_PRIOR && vk <= VK_DOWN) || vk == VK_INSERT || vk == VK_DELETE;
        lp = 1 | ((LPARAM)sc << 16) | (ext ? (LPARAM)1 << 24 : 0) | (down ? 0 : ((LPARAM)1 << 30 | (LPARAM)1 << 31));
    };
    auto button = [&](int b) {
        static const UINT kDown[] = { 0, WM_LBUTTONDOWN, WM_RBUTTONDOWN, WM_MBUTTONDOWN, WM_XBUTTONDOWN, WM_XBUTTONDOWN };
        static const UINT kUp[] = { 0, WM_LBUTTONUP, WM_RBUTTONUP, WM_MBUTTONUP, WM_XBUTTONUP, WM_XBUTTONUP };
        static const WPARAM kMk[] = { 0, MK_LBUTTON, MK_RBUTTON, MK_MBUTTON, MK_XBUTTON1, MK_XBUTTON2 };
        msg = down ? kDown[b] : kUp[b];
        wp = (down ? kMk[b] : 0) | (b == 4 ? (WPARAM)XBUTTON1 << 16 : b == 5 ? (WPARAM)XBUTTON2 << 16 : 0);
        lp = MAKELPARAM(g_vpW / 2, g_vpH / 2);
    };
    if (kind >= 0 && kind < 3) {
        const TapKey& t = g_tapKey[kind];
        if (t.button) button(t.button); else if (t.vk) key(t.vk);
    } else if (kind == hn::kTapSlot && slot >= 1 && slot <= 4) {
        key('0' + slot);
    }
    if (msg) CallWindowProcW(g_orig, h, msg, wp, lp);
}

LRESULT CALLBACK proc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == WM_HN_TAP) {
        deliverTap(h, (int)wp, (int)(lp & 0xFF), (lp & 0x100) == 0);
        return 0;
    }
    if (!g_alive.load(std::memory_order_relaxed)) {
        if (g_routedSinceAlive) { g_routedSinceAlive = false; std::memset(g_keyOwner, 0, sizeof(g_keyOwner)); }
        return CallWindowProcW(g_orig, h, msg, wp, lp);
    }
    // Hello Neighbor's own menu (paused): it gets every key, click and wheel. Keys still held by Minecraft are
    // released once, when the menu opens, so nothing stays pressed underneath.
    bool hostMenu = (std::atomic_ref<uint32_t>(((hn::OverlayHeader*)g_base)->mcFlags).load(std::memory_order_acquire) & hn::kOverlayHostMenu) != 0;
    if (hostMenu != g_wasHostMenu) {
        g_wasHostMenu = hostMenu;
        if (hostMenu) releaseAll();
        g_cursorVisible = false;
    }
    if (hostMenu) return CallWindowProcW(g_orig, h, msg, wp, lp);

    bool screen = screenOpen();
    if (screen != g_wasScreenOpen) {
        g_wasScreenOpen = screen;
        if (screen) {                     // centre the cursor when a Minecraft screen opens
            g_curX = g_vpW / 2; g_curY = g_vpH / 2;
            push(hn::kInCursor, 0, g_curX, g_curY);
        }
        g_cursorVisible = screen;
    }

    switch (msg) {
    case WM_KEYDOWN: case WM_SYSKEYDOWN: case WM_KEYUP: case WM_SYSKEYUP: {
        int sc = (int)((lp >> 16) & 0x7F);
        int hid = g_set1[(lp >> 24) & 1][sc];
        if (!hid) break;
        bool down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
        if (down) {
            bool toMc = screen || minecraftKey(hid);
            if (g_keyOwner[hid] == 0) g_keyOwner[hid] = toMc ? 1 : 2;   // auto-repeat keeps the first owner
            if (g_keyOwner[hid] == 1) {
                push(hn::kInKey, (uint16_t)hid, 1);
                if (mirrorToGame(hid, screen)) break;    // ...and to the game too
                return 0;
            }
        } else {
            uint8_t owner = g_keyOwner[hid];
            g_keyOwner[hid] = 0;
            if (owner == 1 || (owner == 0 && screen)) {
                push(hn::kInKey, (uint16_t)hid, 0);
                if (owner == 1 && mirrorToGame(hid, screen)) break;
                return 0;
            }
        }
        break;
    }
    case WM_CHAR:
        if (screen) { if (wp >= 32) push(hn::kInText, 0, (int)wp); return 0; }
        break;
    case WM_LBUTTONDOWN: case WM_LBUTTONDBLCLK: return button(msg, 1, true);
    case WM_LBUTTONUP: return button(msg, 1, false);
    case WM_MBUTTONDOWN: case WM_MBUTTONDBLCLK: return button(msg, 2, true);
    case WM_MBUTTONUP: return button(msg, 2, false);
    case WM_RBUTTONDOWN: case WM_RBUTTONDBLCLK: return button(msg, 3, true);
    case WM_RBUTTONUP: return button(msg, 3, false);
    case WM_MOUSEWHEEL:
        push(hn::kInScroll, 0, GET_WHEEL_DELTA_WPARAM(wp));
        return 0;
    case WM_INPUT:
        if (screen) {
            RAWINPUT ri; UINT size = sizeof(ri);
            if (GetRawInputData((HRAWINPUT)lp, RID_INPUT, &ri, &size, sizeof(RAWINPUTHEADER)) != (UINT)-1 &&
                ri.header.dwType == RIM_TYPEMOUSE && !(ri.data.mouse.usFlags & MOUSE_MOVE_ABSOLUTE) &&
                (ri.data.mouse.lLastX || ri.data.mouse.lLastY)) {
                int x = g_curX + ri.data.mouse.lLastX, y = g_curY + ri.data.mouse.lLastY;
                x = x < 0 ? 0 : (x >= g_vpW ? g_vpW - 1 : x);
                y = y < 0 ? 0 : (y >= g_vpH ? g_vpH - 1 : y);
                g_curX = x; g_curY = y;
                push(hn::kInCursor, 0, x, y);
            }
            return DefWindowProcW(h, msg, wp, lp);   // the game's camera must not move; DefWindowProc cleans up
        }
        break;
    case WM_MOUSEMOVE:
        if (screen) return 0;
        break;
    case WM_KILLFOCUS:
        releaseAll();
        break;
    case WM_ACTIVATEAPP:
        if (!wp) releaseAll();
        break;
    default:
        break;
    }
    return CallWindowProcW(g_orig, h, msg, wp, lp);
}

}  // namespace

bool install(HWND gameWindow, uint8_t* overlayBase, LogFn log) {
    if (g_orig || !gameWindow || !overlayBase) return g_orig != nullptr;
    initTable();
    g_hwnd = gameWindow;
    g_base = overlayBase;
    g_log = log;
    g_orig = (WNDPROC)SetWindowLongPtrW(gameWindow, GWLP_WNDPROC, (LONG_PTR)&proc);
    if (g_log) g_log("input: game window %p subclassed (orig proc %p)", (void*)gameWindow, (void*)g_orig);
    return g_orig != nullptr;
}

void setMinecraftAlive(bool alive) {
    bool was = g_alive.exchange(alive);
    if (was != alive && g_log) g_log("input: Minecraft %s; routing %s (events sent %llu, dropped %llu)", alive ? "alive" : "gone",
                                     alive ? "ON" : "OFF, everything goes to the game", g_pushed, g_dropped);
    if (!alive) g_cursorVisible = false;
}

void setViewport(int w, int h) { if (w > 0 && h > 0) { g_vpW = w; g_vpH = h; } }

void pumpTaps() {
    if (!g_orig || !g_base) return;
    hn::OverlayHeader* hd = (hn::OverlayHeader*)g_base;
    if (--g_keysFrames <= 0) { g_keysFrames = 120; loadTapKeys(); }   // ~2 s
    bool alive = g_alive.load(std::memory_order_relaxed);
    static const char* kNames[] = { "pick up / interact", "use", "throw", "slot key" };
    // Use / apply / throw: Hello Neighbor's key mirrors Minecraft's (down on a new press, up once released and held
    // for at least kTapHoldFrames). Several can be down at once.
    for (int k = 0; k < 3; ++k) {
        uint32_t v = std::atomic_ref<uint32_t>(hd->hostTaps[k]).load(std::memory_order_acquire);
        uint32_t count = v >> 1;
        bool held = (v & 1) != 0 && alive;
        // First look, or Minecraft restarted (its counters start again at 0): nothing to replay.
        if (!g_tapInit || count < g_tapSeen[k]) g_tapSeen[k] = count;
        if (count > g_tapSeen[k]) {
            g_tapSeen[k] = count;
            if (!g_holdDown[k]) {
                g_holdDown[k] = true;
                g_holdFrames[k] = 0;
                PostMessageW(g_hwnd, WM_HN_TAP, (WPARAM)k, 0);
                if (g_log && (++g_taps <= 20 || g_taps % 100 == 0)) g_log("input: press %llu -> Hello Neighbor %s", g_taps, kNames[k]);
            }
        }
        if (g_holdDown[k] && ++g_holdFrames[k] >= kTapHoldFrames && !held) {
            g_holdDown[k] = false;
            PostMessageW(g_hwnd, WM_HN_TAP, (WPARAM)k, 0x100);
            if (g_log && g_taps <= 20) g_log("input: release -> Hello Neighbor %s after %d frames", kNames[k], g_holdFrames[k]);
        }
    }
    // Slot keys: short taps, queued.
    {
        const int k = hn::kTapSlot;
        uint32_t v = std::atomic_ref<uint32_t>(hd->hostTaps[k]).load(std::memory_order_acquire);
        uint32_t count = v & 0xFFFFFF;
        if (!g_tapInit || count < g_tapSeen[k]) g_tapSeen[k] = count;
        while (g_tapSeen[k] < count) {
            ++g_tapSeen[k];
            if (g_tapQueued < 16) { g_tapQueue[g_tapQueued][0] = k; g_tapQueue[g_tapQueued][1] = (int)(v >> 24); ++g_tapQueued; }
        }
    }
    g_tapInit = true;
    if (g_tapActive[0] >= 0) {
        if (--g_tapFrames <= 0) {
            PostMessageW(g_hwnd, WM_HN_TAP, (WPARAM)g_tapActive[0], (LPARAM)(g_tapActive[1] | 0x100));
            g_tapActive[0] = -1;
        }
        return;
    }
    if (g_tapQueued == 0) return;
    g_tapActive[0] = g_tapQueue[0][0];
    g_tapActive[1] = g_tapQueue[0][1];
    std::memmove(g_tapQueue[0], g_tapQueue[1], sizeof(g_tapQueue[0]) * (size_t)(--g_tapQueued));
    g_tapFrames = kTapHoldFrames;
    PostMessageW(g_hwnd, WM_HN_TAP, (WPARAM)g_tapActive[0], (LPARAM)g_tapActive[1]);
    if (g_log && (++g_taps <= 20 || g_taps % 100 == 0)) g_log("input: tap %llu -> Hello Neighbor slot %d", g_taps, g_tapActive[1]);
}
bool cursorVisible() { return g_cursorVisible.load(); }
int cursorX() { return g_curX.load(); }
int cursorY() { return g_curY.load(); }

}  // namespace hninput
