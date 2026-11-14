package com.ezvisuals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lua scripts for EzVisuals. Folder: .minecraft/config/ezvisuals/scripts/*.lua
 * Scripts are sandboxed (no io/os/require/java access), visual-only API, hot-reload on file change.
 * Script hooks: onTick() and onHud(tickDelta).
 */
public final class LuaScripts {
    private LuaScripts() {}

    public static final class Script {
        public final String file;
        public volatile boolean enabled;
        public volatile String error = "";
        long mtime = -1;
        Globals g;
        LuaValue onTick, onHud;
        Script(String file) { this.file = file; }
    }

    public static final List<Script> LIST = new CopyOnWriteArrayList<>();
    private static final Set<String> ENABLED = new HashSet<>();
    private static final Logger LOG = LoggerFactory.getLogger("ezvisuals-lua");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,40}\\.lua");
    private static final Pattern TAG = Pattern.compile("(?m)^--@name\\s+([A-Za-z0-9_\\-]{1,40})");
    private static final int MAX_BYTES = 256 * 1024;

    private static DrawContext ctx;
    private static long deadline;
    private static int scanCd;
    private static boolean stateLoaded;

    // ---------------- files / state ----------------
    public static Path dir() {
        Path d = Config.dir().resolve("scripts");
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        return d;
    }

    private static void loadState() {
        stateLoaded = true;
        try {
            Path f = dir().resolve("_enabled.txt");
            if (Files.exists(f)) ENABLED.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static void saveState() {
        try { Files.write(dir().resolve("_enabled.txt"), ENABLED); } catch (Exception ignored) {}
    }

    public static String source() {
        try {
            Path f = dir().resolve("_source.txt");
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) { return ""; }
    }

    public static void setSource(String url) {
        try { Files.writeString(dir().resolve("_source.txt"), url == null ? "" : url.trim(), StandardCharsets.UTF_8); }
        catch (Exception ignored) {}
    }

    private static Script find(String n) {
        for (Script s : LIST) if (s.file.equals(n)) return s;
        return null;
    }

    /** Rescans the folder: new files appear, changed enabled files are reloaded, deleted files vanish. */
    public static void scan() {
        if (!stateLoaded) loadState();
        Set<String> found = new HashSet<>();
        try (var st = Files.list(dir())) {
            for (Path p : st.sorted().toList()) {
                String n = p.getFileName().toString();
                if (!NAME.matcher(n).matches()) continue;
                found.add(n);
                long mt = Files.getLastModifiedTime(p).toMillis();
                Script s = find(n);
                if (s == null) { s = new Script(n); s.enabled = ENABLED.contains(n); LIST.add(s); }
                if (s.mtime != mt) {
                    s.mtime = mt;
                    if (s.enabled) load(s); else unload(s);
                }
            }
        } catch (Exception e) {
            LOG.warn("scan failed: {}", e.toString());
        }
        LIST.removeIf(s -> !found.contains(s.file));
    }

    public static void setEnabled(Script s, boolean on) {
        s.enabled = on;
        if (on) { ENABLED.add(s.file); load(s); } else { ENABLED.remove(s.file); unload(s); }
        saveState();
    }

    public static void delete(Script s) {
        try { Files.deleteIfExists(dir().resolve(s.file)); } catch (Exception ignored) {}
        ENABLED.remove(s.file);
        saveState();
        LIST.remove(s);
    }

    public static void reloadAll() {
        for (Script s : LIST) if (s.enabled) load(s);
    }

    /** Saves pasted Lua code. Name comes from a "--@name foo" line, otherwise it is generated. */
    public static String installFromText(String code) {
        if (code == null || code.isBlank()) return "Clipboard is empty";
        if (code.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "Script is too big";
        Matcher m = TAG.matcher(code);
        String name = (m.find() ? m.group(1) : "script_" + (System.currentTimeMillis() / 1000 % 1_000_000)) + ".lua";
        try {
            Files.writeString(dir().resolve(name), code, StandardCharsets.UTF_8);
            scan();
            return "Saved " + name + " (turn it ON in the list)";
        } catch (Exception e) {
            return "Save failed: " + e.getMessage();
        }
    }

    // ---------------- cloud update (GitHub raw etc.) ----------------
    /**
     * Manifest JSON: {"scripts":[{"name":"fpsbox.lua","url":"https://raw.githubusercontent.com/.../fpsbox.lua"}]}
     * Existing scripts keep their ON/OFF state and hot-reload; new ones start OFF.
     */
    public static void update(String manifestUrl, Consumer<String> status) {
        Thread th = new Thread(() -> {
            try {
                if (manifestUrl == null || !manifestUrl.startsWith("https://")) { status.accept("URL must start with https://"); return; }
                HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                JsonArray arr = JsonParser.parseString(get(hc, manifestUrl)).getAsJsonObject().getAsJsonArray("scripts");
                int ok = 0;
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    String n = o.get("name").getAsString(), u = o.get("url").getAsString();
                    if (!NAME.matcher(n).matches() || !u.startsWith("https://")) continue;
                    Files.writeString(dir().resolve(n), get(hc, u), StandardCharsets.UTF_8);
                    ok++;
                }
                status.accept("Updated " + ok + " script(s)");
            } catch (Exception e) {
                status.accept("Update failed: " + e.getMessage());
            }
        }, "ezvisuals-lua-update");
        th.setDaemon(true);
        th.start();
    }

    private static String get(HttpClient hc, String u) throws Exception {
        HttpResponse<byte[]> r = hc.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode());
        if (r.body().length > MAX_BYTES) throw new RuntimeException("file too big");
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // ---------------- running ----------------
    private static void unload(Script s) {
        s.g = null; s.onTick = null; s.onHud = null; s.error = "";
    }

    private static void fail(Script s, Throwable t) {
        String m = t.getMessage() == null ? t.toString() : t.getMessage();
        s.error = m.length() > 160 ? m.substring(0, 160) : m;
        s.enabled = false;
        ENABLED.remove(s.file);
        saveState();
        s.g = null; s.onTick = null; s.onHud = null;
        LOG.warn("script {} disabled: {}", s.file, m);
    }

    private static void load(Script s) {
        unload(s);
        try {
            Path p = dir().resolve(s.file);
            if (Files.size(p) > MAX_BYTES) throw new RuntimeException("file too big");
            String code = Files.readString(p, StandardCharsets.UTF_8);
            Globals g = sandbox();
            s.g = g;
            deadline = System.nanoTime() + 200_000_000L;
            g.load(code, s.file).call();
            LuaValue t = g.get("onTick"), h = g.get("onHud");
            s.onTick = t.isfunction() ? t : null;
            s.onHud = h.isfunction() ? h : null;
        } catch (Throwable t) {
            fail(s, t);
        }
    }

    private static void call(Script s, LuaValue f, LuaValue arg, long budgetMs) {
        if (f == null) return;
        deadline = System.nanoTime() + budgetMs * 1_000_000L;
        try { f.call(arg); } catch (Throwable t) { fail(s, t); }
    }

    public static void tick(MinecraftClient mc) {
        if (!stateLoaded) loadState();
        if (--scanCd <= 0) { scanCd = 40; scan(); }
        if (mc.player == null) return;
        for (Script s : LIST) if (s.enabled) call(s, s.onTick, LuaValue.NIL, 10);
    }

    public static void hud(DrawContext c, float td) {
        ctx = c;
        try {
            for (Script s : LIST) if (s.enabled) call(s, s.onHud, LuaValue.valueOf(td), 5);
        } finally {
            ctx = null;
        }
    }

    // ---------------- sandbox + API ----------------
    private static LuaValue fn(Function<Varargs, Varargs> f) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) { return f.apply(a); }
        };
    }

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    private static double unsigned(int c) { return (double) (c & 0xFFFFFFFFL); }

    private static Globals sandbox() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new Bit32Lib());
        g.load(new MathLib());
        g.load(new DebugLib());
        LoadState.install(g);
        LuaC.install(g);
        g.set("dofile", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);
        g.set("print", fn(a -> { LOG.info("[lua] {}", a.tojstring(1)); return LuaValue.NONE; }));

        // watchdog: stop scripts that run too long (infinite loops)
        LuaValue hook = fn(a -> {
            if (System.nanoTime() > deadline) throw new LuaError("script timeout");
            return LuaValue.NONE;
        });
        try {
            g.get("debug").get("sethook").invoke(LuaValue.varargsOf(
                    new LuaValue[]{hook, LuaValue.valueOf(""), LuaValue.valueOf(5000)}));
        } catch (Throwable t) {
            throw new RuntimeException("watchdog unavailable, script not started");
        }
        g.set("debug", LuaValue.NIL);

        LuaTable ez = new LuaTable();
        ez.set("log", fn(a -> { LOG.info("[lua] {}", a.tojstring(1)); return LuaValue.NONE; }));
        ez.set("msg", fn(a -> {
            if (mc().player != null) mc().player.sendMessage(Text.literal(a.checkjstring(1)), true);
            return LuaValue.NONE;
        }));
        ez.set("fps", fn(a -> LuaValue.valueOf(mc().getCurrentFps())));
        ez.set("tps", fn(a -> LuaValue.valueOf(Tps.str())));
        ez.set("now", fn(a -> LuaValue.valueOf((System.currentTimeMillis() % 100_000_000L) / 1000.0)));
        ez.set("size", fn(a -> LuaValue.varargsOf(
                LuaValue.valueOf(mc().getWindow().getScaledWidth()),
                LuaValue.valueOf(mc().getWindow().getScaledHeight()))));
        ez.set("theme", fn(a -> {
            Theme t = Theme.cur();
            LuaTable r = new LuaTable();
            r.set("accent", LuaValue.valueOf(unsigned(t.accent)));
            r.set("accent2", LuaValue.valueOf(unsigned(t.accent2)));
            return r;
        }));
        ez.set("player", fn(a -> {
            ClientPlayerEntity p = mc().player;
            if (p == null) return LuaValue.NIL;
            LuaTable r = new LuaTable();
            r.set("name", LuaValue.valueOf(p.getName().getString()));
            r.set("x", LuaValue.valueOf(p.getX()));
            r.set("y", LuaValue.valueOf(p.getY()));
            r.set("z", LuaValue.valueOf(p.getZ()));
            r.set("yaw", LuaValue.valueOf((double) p.getYaw()));
            r.set("pitch", LuaValue.valueOf((double) p.getPitch()));
            r.set("health", LuaValue.valueOf((double) p.getHealth()));
            r.set("onGround", LuaValue.valueOf(p.isOnGround()));
            return r;
        }));

        // drawing (works only inside onHud)
        ez.set("rect", fn(a -> {
            if (ctx != null) {
                int x = a.checkint(1), y = a.checkint(2);
                ctx.fill(x, y, x + a.checkint(3), y + a.checkint(4), (int) a.checklong(5));
            }
            return LuaValue.NONE;
        }));
        ez.set("rrect", fn(a -> {
            if (ctx != null) Draw.rrect(ctx, a.checkint(1), a.checkint(2), a.checkint(3), a.checkint(4),
                    a.checkint(5), (int) a.checklong(6));
            return LuaValue.NONE;
        }));
        ez.set("text", fn(a -> {
            if (ctx != null) {
                String s = a.checkjstring(1);
                int x = a.checkint(2), y = a.checkint(3), col = (int) a.checklong(4);
                if (a.optboolean(5, false)) Draw.shadow(ctx, s, x, y, col); else Draw.text(ctx, s, x, y, col);
            }
            return LuaValue.NONE;
        }));
        ez.set("width", fn(a -> LuaValue.valueOf(Draw.w(a.checkjstring(1)))));

        // modules: read any, switch only Visuals / HUD ones
        ez.set("enabled", fn(a -> {
            String id = a.checkjstring(1);
            for (Module m : Modules.ALL) if (m.id.equals(id)) return LuaValue.valueOf(m.enabled);
            return LuaValue.NIL;
        }));
        ez.set("setEnabled", fn(a -> {
            String id = a.checkjstring(1);
            for (Module m : Modules.ALL) {
                if (m.id.equals(id) && (m.category.equals("Visuals") || m.category.equals("HUD"))) {
                    m.enabled = a.checkboolean(2);
                }
            }
            return LuaValue.NONE;
        }));
        g.set("ez", ez);
        return g;
    }
}
