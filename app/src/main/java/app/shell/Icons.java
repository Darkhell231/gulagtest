package app.shell;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Подтягивает иконку и заголовок сайта. HTTP-запросы идут через сетевой конфиг приложения (с нашим сертификатом). */
final class Icons {
    private Icons() {}

    static final int N = 128;
    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static final class Res {
        String title;
        Bitmap icon; // уже "собранная" (compose)
    }

    private static final class Conn {
        String url;
        InputStream in;
    }

    private static final class Cand {
        String url;
        float score;
    }

    static Res fetch(String pageUrl) {
        Res r = new Res();
        String html = "";
        String base = pageUrl;
        try {
            Conn c = open(pageUrl);
            base = c.url;
            html = new String(Io.readAll(c.in, 300000), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }

        Matcher t = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (t.find()) r.title = cleanTitle(t.group(1));

        List<Cand> cands = new ArrayList<>();
        Matcher m = Pattern.compile("<link\\s[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String tag = m.group();
            String rel = attr(tag, "rel");
            String href = attr(tag, "href");
            if (rel == null || href == null) continue;
            rel = rel.toLowerCase(Locale.ROOT);
            float sc;
            if (rel.contains("apple-touch-icon")) sc = 3;
            else if (rel.contains("icon")) sc = 2;
            else continue;
            String sizes = attr(tag, "sizes");
            if (sizes != null) {
                Matcher sm = Pattern.compile("(\\d+)").matcher(sizes);
                if (sm.find()) sc += Math.min(Integer.parseInt(sm.group(1)), 512) / 1000f;
            }
            try {
                Cand c = new Cand();
                c.url = new URL(new URL(base), href.trim()).toString();
                c.score = sc;
                cands.add(c);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(cands, new Comparator<Cand>() {
            @Override
            public int compare(Cand a, Cand b) {
                return Float.compare(b.score, a.score);
            }
        });
        try {
            Cand f = new Cand();
            f.url = new URL(new URL(base), "/favicon.ico").toString();
            cands.add(f);
        } catch (Exception ignored) {
        }

        int tries = 0;
        for (Cand c : cands) {
            if (tries++ >= 5 || !c.url.startsWith("https://")) continue;
            try {
                Conn cn = open(c.url);
                byte[] b = Io.readAll(cn.in, 600000);
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm != null && bm.getWidth() >= 16) {
                    r.icon = compose(bm);
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    private static Conn open(String u) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "ru,en;q=0.8");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect");
                u = new URL(new URL(u), loc).toString();
                if (!u.startsWith("https://")) throw new IOException("not https");
                continue;
            }
            if (code != 200) throw new IOException("HTTP " + code);
            Conn r = new Conn();
            r.url = u;
            r.in = c.getInputStream();
            return r;
        }
        throw new IOException("too many redirects");
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*([\"'])(.*?)\\1",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(tag);
        return m.find() ? m.group(2) : null;
    }

    private static String cleanTitle(String s) {
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
        for (String sep : new String[]{" — ", " – ", " | ", " - ", " • "}) {
            int i = s.indexOf(sep);
            if (i > 2) {
                s = s.substring(0, i);
                break;
            }
        }
        if (s.length() > 24) s = s.substring(0, 24).trim();
        return s.isEmpty() ? null : s;
    }

    /** Приводит иконку к квадрату 128x128: непрозрачные — на всю площадь, прозрачные — на белом фоне с отступом. */
    static Bitmap compose(Bitmap s) {
        Bitmap o = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(o);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean opaque = !s.hasAlpha()
                || (Color.alpha(s.getPixel(0, 0)) > 240 && Color.alpha(s.getPixel(s.getWidth() - 1, s.getHeight() - 1)) > 240);
        if (opaque) {
            c.drawBitmap(s, null, new Rect(0, 0, N, N), p);
        } else {
            Paint bg = new Paint();
            bg.setColor(Color.WHITE);
            c.drawRect(0, 0, N, N, bg);
            int i = Math.round(N * 0.16f);
            c.drawBitmap(s, null, new Rect(i, i, N - i, N - i), p);
        }
        return o;
    }

    /** Заглушка: цветной квадрат с буквой. */
    static Bitmap letter(String name, int px) {
        Bitmap o = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(o);
        int h = Math.abs(name.hashCode());
        float[] hsv = {h % 360, 0.55f, 0.65f};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.HSVToColor(hsv));
        c.drawRect(0, 0, px, px, p);
        p.setColor(Color.WHITE);
        p.setTextSize(px * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        c.drawText(l, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p);
        return o;
    }
}
