package app.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Список "приложений" (сайтов) и их иконки. */
final class Store {
    static final class Site {
        String id, name, url;
        boolean autoName;   // имя взято автоматически (из <title>/хоста)
        boolean customIcon; // иконка выбрана пользователем
    }

    final List<Site> sites = new ArrayList<>();
    private final File file;
    private final File iconDir;
    private final Map<String, Bitmap> cache = new HashMap<>();

    Store(Context c) {
        File root = c.getFilesDir();
        file = new File(root, "sites.json");
        iconDir = new File(root, "icons");
        iconDir.mkdirs();
        try {
            JSONArray a = new JSONArray(new String(Io.readFile(file), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Site s = new Site();
                s.id = o.getString("id");
                s.name = o.getString("name");
                s.url = o.getString("url");
                s.autoName = o.optBoolean("auto");
                s.customIcon = o.optBoolean("custom");
                sites.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    void save() {
        try {
            JSONArray a = new JSONArray();
            for (Site s : sites) {
                a.put(new JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
                        .put("auto", s.autoName).put("custom", s.customIcon));
            }
            Io.writeAtomic(file, a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    Site add(String name, String url) {
        Site s = new Site();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        s.url = url;
        name = name == null ? "" : name.trim();
        s.autoName = name.isEmpty();
        s.name = name.isEmpty() ? bare(host(url)) : name;
        sites.add(s);
        save();
        return s;
    }

    Site byUrl(String url) {
        for (Site s : sites) if (s.url.equalsIgnoreCase(url)) return s;
        return null;
    }

    void remove(Site s) {
        sites.remove(s);
        cache.remove(s.id);
        new File(iconDir, s.id + ".png").delete();
        save();
    }

    Bitmap icon(Site s) {
        Bitmap b = cache.get(s.id);
        if (b == null) {
            File f = new File(iconDir, s.id + ".png");
            if (f.exists()) {
                b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) cache.put(s.id, b);
            }
        }
        return b;
    }

    byte[] iconBytes(Site s) {
        try {
            File f = new File(iconDir, s.id + ".png");
            return f.exists() ? Io.readFile(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    void setIcon(Site s, Bitmap composed) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            composed.compress(Bitmap.CompressFormat.PNG, 100, o);
            Io.writeAtomic(new File(iconDir, s.id + ".png"), o.toByteArray());
            cache.put(s.id, composed);
        } catch (Exception ignored) {
        }
    }

    /** true, если host совпадает с адресом любого сохранённого сайта (или его поддомен). */
    boolean knowsHost(String host) {
        for (Site s : sites) if (hostMatch(host, s.url)) return true;
        return false;
    }

    // ---------- URL-утилиты ----------

    static String normalize(CharSequence in) {
        if (in == null) return null;
        String s = in.toString().trim();
        if (s.isEmpty()) return null;
        if (s.regionMatches(true, 0, "http://", 0, 7)) s = "https://" + s.substring(7);
        else if (!s.contains("://")) s = "https://" + s;
        try {
            Uri u = Uri.parse(s);
            String h = u.getHost();
            if (!"https".equals(u.getScheme()) || h == null || h.indexOf('.') < 0) return null;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    static String bare(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    /** Совпадает ли хост страницы с сохранённым адресом/доменом. */
    static boolean hostMatch(String pageHost, String saved) {
        if (pageHost == null || saved == null) return false;
        String sh = saved.trim().toLowerCase(Locale.ROOT);
        if (sh.contains("://")) sh = host(sh);
        int i = sh.indexOf('/');
        if (i > 0) sh = sh.substring(0, i);
        sh = bare(sh);
        String ph = bare(pageHost.toLowerCase(Locale.ROOT));
        return !sh.isEmpty() && (ph.equals(sh) || ph.endsWith("." + sh));
    }
}
