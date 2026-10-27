package app.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int R_EXPORT_SITES = 1, R_IMPORT_SITES = 2, R_EXPORT_VAULT = 3,
            R_IMPORT_VAULT = 4, R_IMPORT_CERT = 5, R_PICK_ICON = 6, R_FILE = 7;
    private static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private static final int MAX_WINDOWS = 8;
    private static final long AUTOLOCK_MS = 5 * 60 * 1000L;
    private static final int WHITE = Color.WHITE;

    private enum Mode { HOME, APP, RECENTS }

    /** Открытое "приложение" (вкладка). */
    private static final class Win {
        Store.Site site;
        WebView web;
        Bitmap snap;
    }

    private Store store;
    private Vault vault;
    private Certs certs;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private FrameLayout stage, home, webHost, recents;
    private LinearLayout grid;
    private ProgressBar bar;
    private TextView fab;

    private Mode mode = Mode.HOME, prevMode = Mode.HOME;
    private final LinkedHashMap<String, Win> wins = new LinkedHashMap<>(); // порядок = давность
    private Win current;
    private final Set<String> sslOk = new HashSet<>();

    private ValueCallback<Uri[]> fileCb;
    private Store.Site iconTarget;
    private byte[] pendingVaultImport;
    private long leftAt;

    // ====================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", 0);
        store = new Store(this);
        vault = new Vault(this);
        certs = new Certs(this);
        applySecure();
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildNav(), new LinearLayout.LayoutParams(-1, U.dp(this, 46)));
        setContentView(root);

        buildHome();
        webHost = new FrameLayout(this);
        recents = new FrameLayout(this);
        recents.setBackgroundColor(0xF0101018);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        fab = U.center(U.tv(this, "🔑", 22, WHITE));
        fab.setBackground(U.box(this, 0xCC3F51B5, 24));
        fab.setOnClickListener(v -> openVault(current));

        stage.addView(home, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(webHost, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(recents, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(bar, new FrameLayout.LayoutParams(-1, U.dp(this, 3), Gravity.TOP));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(U.dp(this, 48), U.dp(this, 48),
                Gravity.END | Gravity.BOTTOM);
        fl.setMargins(0, 0, U.dp(this, 14), U.dp(this, 14));
        stage.addView(fab, fl);

        refreshGrid();
        setMode(Mode.HOME);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > AUTOLOCK_MS) vault.lock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        leftAt = SystemClock.elapsedRealtime();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Win w : wins.values()) w.web.destroy();
        wins.clear();
        bg.shutdown();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        refreshGrid();
    }

    @Override
    public void onBackPressed() {
        if (mode == Mode.RECENTS) {
            setMode(prevMode == Mode.APP && current != null ? Mode.APP : Mode.HOME);
        } else if (mode == Mode.APP) {
            if (current != null && current.web.canGoBack()) current.web.goBack();
            else goHome();
        } else {
            moveTaskToBack(true);
        }
    }

    private void applySecure() {
        if (prefs.getBoolean("secure", true)) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // ====================================================================== shell UI

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(Color.BLACK);
        nav.setGravity(Gravity.CENTER);
        final View.OnClickListener[] ls = {
                v -> onBackPressed(),
                v -> goHome(),
                v -> { if (mode == Mode.RECENTS) onBackPressed(); else showRecents(); }
        };
        for (int i = 0; i < 3; i++) {
            final int type = i;
            View v = new View(this) {
                final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                @Override
                protected void onDraw(Canvas c) {
                    float w = getWidth(), h = getHeight(), r = U.dp(getContext(), 8);
                    p.setColor(0xFFDDDDDD);
                    if (type == 0) {
                        android.graphics.Path path = new android.graphics.Path();
                        path.moveTo(w / 2 + r, h / 2 - r);
                        path.lineTo(w / 2 - r, h / 2);
                        path.lineTo(w / 2 + r, h / 2 + r);
                        path.close();
                        c.drawPath(path, p);
                    } else if (type == 1) {
                        c.drawCircle(w / 2, h / 2, r, p);
                    } else {
                        c.drawRoundRect(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r, 3, 3, p);
                    }
                }
            };
            v.setOnClickListener(ls[i]);
            nav.addView(v, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        return nav;
    }

    private void buildHome() {
        home = new FrameLayout(this);
        home.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF141E30, 0xFF243B55, 0xFF3A1C71}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(U.dp(this, 8), U.dp(this, 16), U.dp(this, 8), U.dp(this, 8));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(52);
        clock.setTextColor(WHITE);
        clock.setGravity(Gravity.CENTER);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(15);
        date.setTextColor(0xCCFFFFFF);
        date.setGravity(Gravity.CENTER);
        col.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        col.addView(date, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, U.dp(this, 20), 0, 0);
        sv.addView(grid, new ViewGroup.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout dock = new LinearLayout(this);
        dock.setBackground(U.box(this, 0x33FFFFFF, 26));
        dock.setPadding(U.dp(this, 6), U.dp(this, 8), U.dp(this, 6), U.dp(this, 8));
        dock.addView(dockItem("🔐", "Сейф", v -> openVault(null)), new LinearLayout.LayoutParams(0, -2, 1f));
        dock.addView(dockItem("📜", "Сертификаты", v -> certsDialog()), new LinearLayout.LayoutParams(0, -2, 1f));
        dock.addView(dockItem("＋", "Добавить", v -> siteDialog(null)), new LinearLayout.LayoutParams(0, -2, 1f));
        dock.addView(dockItem("⋮", "Меню", v -> menuDialog()), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        home.addView(col, new FrameLayout.LayoutParams(-1, -1));
    }

    private View dockItem(String emoji, String label, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        TextView e = U.center(U.tv(this, emoji, 24, WHITE));
        t.addView(e, new LinearLayout.LayoutParams(U.dp(this, 48), U.dp(this, 48)));
        TextView n = U.center(U.tv(this, label, 10, 0xEEFFFFFF));
        n.setSingleLine(true);
        t.addView(n, new LinearLayout.LayoutParams(-2, -2));
        t.setOnClickListener(l);
        return t;
    }

    private void refreshGrid() {
        if (grid == null) return;
        grid.removeAllViews();
        int cols = Math.max(4, Math.min(8, getResources().getConfiguration().screenWidthDp / 84));
        if (store.sites.isEmpty()) {
            TextView h = U.center(U.tv(this, "Нажмите «＋», чтобы добавить сайт\nили импортируйте список в меню ⋮", 14, 0xAAFFFFFF));
            h.setPadding(0, U.dp(this, 40), 0, 0);
            grid.addView(h, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        LinearLayout row = null;
        int i = 0;
        for (Store.Site s : store.sites) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(tile(s), new LinearLayout.LayoutParams(0, -2, 1f));
            i++;
        }
        while (row != null && i % cols != 0) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            i++;
        }
    }

    private Bitmap iconFor(Store.Site s) {
        Bitmap b = store.icon(s);
        return b != null ? b : Icons.letter(s.name, Icons.N);
    }

    private View tile(final Store.Site s) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(U.dp(this, 4), U.dp(this, 8), U.dp(this, 4), U.dp(this, 8));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(iconFor(s));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        U.round(iv, U.dp(this, 14));
        t.addView(iv, new LinearLayout.LayoutParams(U.dp(this, 56), U.dp(this, 56)));
        TextView n = U.center(U.tv(this, s.name, 11, WHITE));
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(0, U.dp(this, 4), 0, 0);
        n.setShadowLayer(3, 0, 1, Color.BLACK);
        t.addView(n, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(v -> open(s));
        t.setOnLongClickListener(v -> { siteMenu(s); return true; });
        return t;
    }

    // ====================================================================== windows (приложения)

    private void setMode(Mode m) {
        mode = m;
        home.setVisibility(m == Mode.HOME ? View.VISIBLE : View.GONE);
        webHost.setVisibility(m == Mode.APP ? View.VISIBLE : View.GONE);
        recents.setVisibility(m == Mode.RECENTS ? View.VISIBLE : View.GONE);
        fab.setVisibility(m == Mode.APP ? View.VISIBLE : View.GONE);
        if (m != Mode.APP) bar.setVisibility(View.GONE);
        for (Win w : wins.values()) {
            boolean on = m == Mode.APP && w == current;
            w.web.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) w.web.onResume(); else w.web.onPause();
        }
    }

    private void open(Store.Site s) {
        Win w = wins.get(s.id);
        if (w == null) {
            w = newWin(s);
            wins.put(s.id, w);
            trimWindows();
        }
        show(w);
    }

    private void show(Win w) {
        current = w;
        wins.remove(w.site.id);
        wins.put(w.site.id, w); // самый свежий — в конец
        setMode(Mode.APP);
    }

    private void goHome() {
        if (mode == Mode.APP) snap(current);
        setMode(Mode.HOME);
    }

    private void trimWindows() {
        Iterator<Win> it = wins.values().iterator();
        while (wins.size() > MAX_WINDOWS && it.hasNext()) {
            Win w = it.next();
            if (w == current) continue;
            it.remove();
            webHost.removeView(w.web);
            w.web.destroy();
        }
    }

    private void closeWin(Win w) {
        wins.remove(w.site.id);
        webHost.removeView(w.web);
        w.web.destroy();
        if (current == w) current = null;
    }

    private void snap(Win w) {
        if (w == null) return;
        try {
            int W = w.web.getWidth(), H = w.web.getHeight();
            if (W <= 0 || H <= 0) return;
            Bitmap b = Bitmap.createBitmap(W / 3, H / 3, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(WHITE);
            c.scale(1 / 3f, 1 / 3f);
            w.web.draw(c);
            w.snap = b;
        } catch (Throwable ignored) {
        }
    }

    private void showRecents() {
        if (mode == Mode.APP) snap(current);
        prevMode = mode;
        buildRecents();
        setMode(Mode.RECENTS);
    }

    private void buildRecents() {
        recents.removeAllViews();
        List<Win> list = new ArrayList<>(wins.values());
        Collections.reverse(list);
        if (list.isEmpty()) {
            TextView t = U.center(U.tv(this, "Нет открытых приложений", 16, 0xAAFFFFFF));
            recents.addView(t, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(U.dp(this, 24), 0, U.dp(this, 24), 0);
        for (final Win w : list) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(U.box(this, 0xFF22222E, 16));
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(U.dp(this, 10), U.dp(this, 8), U.dp(this, 6), U.dp(this, 8));
            ImageView ic = new ImageView(this);
            ic.setImageBitmap(iconFor(w.site));
            U.round(ic, U.dp(this, 6));
            head.addView(ic, new LinearLayout.LayoutParams(U.dp(this, 24), U.dp(this, 24)));
            TextView nm = U.tv(this, w.site.name, 14, WHITE);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setPadding(U.dp(this, 8), 0, 0, 0);
            head.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView x = U.center(U.tv(this, "✕", 18, WHITE));
            x.setOnClickListener(v -> { closeWin(w); buildRecents(); });
            head.addView(x, new LinearLayout.LayoutParams(U.dp(this, 40), U.dp(this, 40)));
            card.addView(head, new LinearLayout.LayoutParams(-1, -2));
            ImageView body = new ImageView(this);
            body.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (w.snap != null) body.setImageBitmap(w.snap);
            else body.setImageBitmap(iconFor(w.site));
            U.round(body, U.dp(this, 12));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
            bl.setMargins(U.dp(this, 6), 0, U.dp(this, 6), U.dp(this, 6));
            card.addView(body, bl);
            card.setOnClickListener(v -> show(w));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(U.dp(this, 220), U.dp(this, 360));
            cl.setMargins(U.dp(this, 8), 0, U.dp(this, 8), 0);
            row.addView(card, cl);
        }
        hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        recents.addView(hs, hl);
        TextView all = U.center(U.tv(this, "Закрыть все", 14, WHITE));
        all.setBackground(U.box(this, 0x33FFFFFF, 20));
        all.setPadding(U.dp(this, 20), U.dp(this, 10), U.dp(this, 20), U.dp(this, 10));
        all.setOnClickListener(v -> {
            for (Win w : new ArrayList<>(wins.values())) closeWin(w);
            current = null;
            setMode(Mode.HOME);
        });
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        al.setMargins(0, 0, 0, U.dp(this, 24));
        recents.addView(all, al);
    }

    // ====================================================================== WebView

    private Win newWin(Store.Site s) {
        Win w = new Win();
        w.site = s;
        WebView v = new WebView(this);
        w.web = v;
        WebSettings st = v.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        // многие банки отказывают встроенным WebView: убираем маркер "; wv"
        st.setUserAgentString(st.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);
        v.setBackgroundColor(WHITE);
        v.setWebViewClient(new Client(w));
        v.setWebChromeClient(new Chrome(w));
        v.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                toast("Скачивание не поддерживается");
            }
        });
        webHost.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.loadUrl(s.url);
        return w;
    }

    private final class Client extends WebViewClient {
        final Win w;

        Client(Win w) { this.w = w; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String sc = u.getScheme();
            if ("https".equals(sc) || "about".equals(sc) || "blob".equals(sc)) return false;
            if ("http".equals(sc)) {
                v.loadUrl(u.buildUpon().scheme("https").build().toString());
                return true;
            }
            if ("tel".equals(sc) || "mailto".equals(sc) || "sms".equals(sc)) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
            }
            return true;
        }

        @Override
        public void onReceivedSslError(WebView v, final SslErrorHandler h, SslError e) {
            final Uri u = Uri.parse(e.getUrl());
            final String host = u.getHost();
            final int port = u.getPort() > 0 ? u.getPort() : 443;
            if (host == null || e.getPrimaryError() != SslError.SSL_UNTRUSTED
                    || Build.VERSION.SDK_INT < 29 || !store.knowsHost(host)) {
                h.cancel();
                toast("Сертификат сайта не доверенный. Импортируйте корневой сертификат (⋮ → Сертификаты)"
                        + " и добавьте этот домен в список сайтов.");
                return;
            }
            final java.security.cert.X509Certificate leaf = e.getCertificate().getX509Certificate();
            if (leaf == null) { h.cancel(); return; }
            String fp;
            try {
                fp = host + ":" + Io.hex(java.security.MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
            } catch (Exception ex) {
                h.cancel();
                return;
            }
            final String key = fp; // кэш по хосту + отпечатку сертификата: подмена сертификата не пройдёт
            if (sslOk.contains(key)) { h.proceed(); return; }
            bg.execute(() -> {
                final boolean ok = certs.verifyHost(host, port, leaf);
                ui.post(() -> {
                    if (ok) { sslOk.add(key); h.proceed(); }
                    else { h.cancel(); toast("Сертификат не подходит к импортированным корневым"); }
                });
            });
        }
    }

    private final class Chrome extends WebChromeClient {
        final Win w;

        Chrome(Win w) { this.w = w; }

        @Override
        public void onProgressChanged(WebView v, int p) {
            if (w != current || mode != Mode.APP) return;
            bar.setProgress(p);
            bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                startActivityForResult(p.createIntent(), R_FILE);
            } catch (Exception e) {
                fileCb = null;
                return false;
            }
            return true;
        }
    }

    // ====================================================================== сайты

    private void siteMenu(final Store.Site s) {
        new AlertDialog.Builder(this, DIALOG).setTitle(s.name)
                .setItems(new String[]{"Открыть", "Изменить", "Обновить иконку", "Удалить"}, (d, i) -> {
                    if (i == 0) open(s);
                    else if (i == 1) siteDialog(s);
                    else if (i == 2) { s.customIcon = false; fetchIcon(s); toast("Загружаю иконку…"); }
                    else deleteSite(s);
                }).show();
    }

    private void deleteSite(final Store.Site s) {
        new AlertDialog.Builder(this, DIALOG).setTitle("Удалить «" + s.name + "»?")
                .setPositiveButton("Удалить", (d, i) -> {
                    Win w = wins.get(s.id);
                    if (w != null) closeWin(w);
                    store.remove(s);
                    refreshGrid();
                }).setNegativeButton("Отмена", null).show();
    }

    private void siteDialog(final Store.Site edit) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(U.dp(this, 20), U.dp(this, 8), U.dp(this, 20), 0);
        final EditText url = field(l, "Адрес, напр. online.sberbank.ru", edit == null ? "" : edit.url, false, null);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText name = field(l, "Название (необязательно)", edit == null || edit.autoName ? "" : edit.name, false, null);
        AlertDialog.Builder b = new AlertDialog.Builder(this, DIALOG)
                .setTitle(edit == null ? "Добавить сайт" : "Изменить")
                .setView(l)
                .setPositiveButton("Сохранить", (d, i) -> {
                    String u = Store.normalize(url.getText());
                    if (u == null) { toast("Некорректный адрес"); return; }
                    String nm = name.getText().toString().trim();
                    if (edit == null) {
                        if (store.byUrl(u) != null) { toast("Такой сайт уже есть"); return; }
                        Store.Site s = store.add(nm, u);
                        refreshGrid();
                        fetchIcon(s);
                    } else {
                        boolean changed = !u.equals(edit.url);
                        edit.url = u;
                        if (!nm.isEmpty()) { edit.name = nm; edit.autoName = false; }
                        else if (!edit.autoName) { edit.autoName = true; edit.name = Store.bare(Store.host(u)); }
                        store.save();
                        refreshGrid();
                        if (changed && !edit.customIcon) fetchIcon(edit);
                    }
                })
                .setNegativeButton("Отмена", null);
        if (edit != null) {
            b.setNeutralButton("Иконка из файла", (d, i) -> {
                iconTarget = edit;
                Intent it = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(it, R_PICK_ICON);
            });
        }
        b.show();
    }

    /** Подтянуть иконку и (если имя авто) название. */
    private void fetchIcon(final Store.Site s) {
        final String url = s.url;
        bg.execute(() -> {
            final Icons.Res r = Icons.fetch(url);
            ui.post(() -> {
                if (!store.sites.contains(s) || !url.equals(s.url)) return;
                if (r.icon != null && !s.customIcon) store.setIcon(s, r.icon);
                if (r.title != null && s.autoName) s.name = r.title;
                store.save();
                refreshGrid();
            });
        });
    }

    private String sitesJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Store.Site s : store.sites) {
            JSONObject o = new JSONObject().put("name", s.autoName ? "" : s.name).put("url", s.url);
            byte[] ic = store.iconBytes(s);
            if (ic != null) o.put("icon", Base64.encodeToString(ic, Base64.NO_WRAP));
            a.put(o);
        }
        return new JSONObject().put("v", 1).put("sites", a).toString();
    }

    private int importSites(String json) throws Exception {
        JSONArray a = new JSONObject(json).getJSONArray("sites");
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            String u = Store.normalize(x.optString("url"));
            if (u == null || store.byUrl(u) != null) continue;
            Store.Site s = store.add(x.optString("name", ""), u);
            String ic = x.optString("icon", "");
            Bitmap bm = null;
            if (!ic.isEmpty()) {
                byte[] pb = Base64.decode(ic, Base64.DEFAULT);
                bm = BitmapFactory.decodeByteArray(pb, 0, pb.length);
            }
            if (bm != null) store.setIcon(s, bm);
            else fetchIcon(s);
            n++;
        }
        return n;
    }

    // ====================================================================== меню / файлы

    private void menuDialog() {
        final boolean secure = prefs.getBoolean("secure", true);
        final String[] items = {
                "Экспорт списка сайтов", "Импорт списка сайтов",
                "Экспорт сейфа (зашифрованный файл)", "Импорт сейфа",
                "Заблокировать сейф",
                "Очистить cookies и кэш",
                "Защита от скриншотов: " + (secure ? "включена" : "выключена")};
        new AlertDialog.Builder(this, DIALOG).setTitle("Меню").setItems(items, (d, i) -> {
            switch (i) {
                case 0: pickCreate(R_EXPORT_SITES, "phoneshell-sites.json", "application/json"); break;
                case 1: pickOpen(R_IMPORT_SITES); break;
                case 2:
                    if (!vault.exists()) toast("Сейф ещё не создан");
                    else pickCreate(R_EXPORT_VAULT, "phoneshell-vault.psv", "application/octet-stream");
                    break;
                case 3: pickOpen(R_IMPORT_VAULT); break;
                case 4: vault.lock(); toast("Сейф заблокирован"); break;
                case 5: clearData(); break;
                case 6:
                    prefs.edit().putBoolean("secure", !secure).apply();
                    applySecure();
                    break;
                default: break;
            }
        }).show();
    }

    private void clearData() {
        new AlertDialog.Builder(this, DIALOG).setTitle("Очистить cookies и кэш?")
                .setMessage("Вы выйдете из аккаунтов на всех сайтах.")
                .setPositiveButton("Очистить", (d, i) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    for (Win w : wins.values()) w.web.clearCache(true);
                    toast("Готово");
                }).setNegativeButton("Отмена", null).show();
    }

    private void pickCreate(int req, String name, String mime) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, req);
    }

    private void pickOpen(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, req);
    }

    private byte[] readUri(Uri u, int max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            return Io.readAll(in, max);
        }
    }

    private void writeUri(final Uri u, final byte[] data, final String okMsg) {
        bg.execute(() -> {
            try (OutputStream o = getContentResolver().openOutputStream(u)) {
                o.write(data);
                ui.post(() -> toast(okMsg));
            } catch (Exception e) {
                ui.post(() -> toast("Не удалось записать файл"));
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == R_FILE) {
            if (fileCb != null) {
                Uri[] r = null;
                if (res == RESULT_OK && data != null) r = WebChromeClient.FileChooserParams.parseResult(res, data);
                fileCb.onReceiveValue(r);
                fileCb = null;
            }
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        try {
            switch (req) {
                case R_EXPORT_SITES:
                    writeUri(u, sitesJson().getBytes(StandardCharsets.UTF_8), "Список сайтов сохранён");
                    break;
                case R_EXPORT_VAULT:
                    writeUri(u, vault.raw(), "Сейф сохранён (файл зашифрован)");
                    break;
                case R_IMPORT_SITES:
                    bg.execute(() -> {
                        try {
                            final String json = new String(readUri(u, 50 << 20), StandardCharsets.UTF_8);
                            ui.post(() -> {
                                try {
                                    int n = importSites(json);
                                    refreshGrid();
                                    toast("Добавлено сайтов: " + n);
                                } catch (Exception e) {
                                    toast("Неверный формат файла");
                                }
                            });
                        } catch (Exception e) {
                            ui.post(() -> toast("Не удалось прочитать файл"));
                        }
                    });
                    break;
                case R_IMPORT_CERT:
                    try {
                        int n = certs.importBytes(readUri(u, 1 << 20));
                        sslOk.clear();
                        toast(n > 0 ? "Сертификатов добавлено: " + n : "Такой сертификат уже есть");
                    } catch (Exception e) {
                        toast("Это не файл сертификата (.crt/.pem/.cer)");
                    }
                    certsDialog();
                    break;
                case R_PICK_ICON:
                    if (iconTarget != null) {
                        byte[] b = readUri(u, 8 << 20);
                        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                        if (bm == null) { toast("Не удалось открыть картинку"); break; }
                        store.setIcon(iconTarget, Icons.compose(bm));
                        iconTarget.customIcon = true;
                        store.save();
                        refreshGrid();
                    }
                    break;
                case R_IMPORT_VAULT:
                    pendingVaultImport = readUri(u, 64 << 20);
                    askImportVaultPassword();
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    // ====================================================================== сертификаты

    private void certsDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(U.dp(this, 20), U.dp(this, 4), U.dp(this, 20), 0);
        TextView note = U.tv(this, "Эти сертификаты действуют только внутри приложения. "
                + "Системное хранилище телефона не затрагивается. "
                + "Импортированный сертификат применяется для сайтов из вашего списка; "
                + "встроенные — для всех запросов приложения.", 12, 0xBBFFFFFF);
        note.setPadding(0, 0, 0, U.dp(this, 10));
        l.addView(note);
        final DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        final AlertDialog.Builder b = new AlertDialog.Builder(this, DIALOG).setTitle("Сертификаты");
        final List<Certs.Item> items = certs.list();
        if (items.isEmpty()) l.addView(U.tv(this, "Пока нет сертификатов", 14, WHITE));
        for (final Certs.Item it : items) {
            TextView t = U.tv(this, it.name() + "\nдо " + df.format(it.cert.getNotAfter())
                    + " · SHA-256 " + it.fingerprint() + "…\n" + (it.bundled ? "встроенный" : "импортирован · нажмите для удаления"), 13, WHITE);
            t.setPadding(0, U.dp(this, 8), 0, U.dp(this, 8));
            if (!it.bundled) {
                t.setOnClickListener(v -> new AlertDialog.Builder(this, DIALOG).setTitle("Удалить сертификат?")
                        .setMessage(it.name())
                        .setPositiveButton("Удалить", (d, i) -> { certs.delete(it); sslOk.clear(); certsDialog(); })
                        .setNegativeButton("Отмена", null).show());
            }
            l.addView(t);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        b.setView(sv)
                .setPositiveButton("Импортировать…", (d, i) -> pickOpen(R_IMPORT_CERT))
                .setNegativeButton("Закрыть", null).show();
    }

    // ====================================================================== сейф

    private void openVault(final Win target) {
        withVault(() -> vaultList(target));
    }

    private void withVault(final Runnable then) {
        if (!vault.exists()) createVaultDialog(then);
        else if (!vault.unlocked()) unlockDialog(then);
        else then.run();
    }

    private void createVaultDialog(final Runnable then) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(U.dp(this, 20), U.dp(this, 8), U.dp(this, 20), 0);
        TextView note = U.tv(this, "Придумайте мастер-пароль. Он нигде не хранится: если забудете, данные не восстановить.", 12, 0xBBFFFFFF);
        l.addView(note);
        final EditText p1 = field(l, "Мастер-пароль (от 8 символов)", "", true, null);
        final EditText p2 = field(l, "Повторите пароль", "", true, null);
        new AlertDialog.Builder(this, DIALOG).setTitle("Создать сейф").setView(l)
                .setPositiveButton("Создать", (d, i) -> {
                    final char[] a = chars(p1), c = chars(p2);
                    if (a.length < 8 || !java.util.Arrays.equals(a, c)) {
                        toast(a.length < 8 ? "Пароль слишком короткий" : "Пароли не совпадают");
                        createVaultDialog(then);
                        return;
                    }
                    toast("Создаю…");
                    bg.execute(() -> {
                        try {
                            vault.create(a);
                            ui.post(then);
                        } catch (Exception e) {
                            ui.post(() -> toast("Ошибка: " + e.getMessage()));
                        } finally {
                            java.util.Arrays.fill(a, ' ');
                            java.util.Arrays.fill(c, ' ');
                        }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    private void unlockDialog(final Runnable then) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(U.dp(this, 20), U.dp(this, 8), U.dp(this, 20), 0);
        final EditText p = field(l, "Мастер-пароль", "", true, null);
        new AlertDialog.Builder(this, DIALOG).setTitle("Сейф заблокирован").setView(l)
                .setPositiveButton("Открыть", (d, i) -> {
                    final char[] a = chars(p);
                    bg.execute(() -> {
                        try {
                            vault.unlock(a);
                            ui.post(then);
                        } catch (java.security.GeneralSecurityException e) {
                            ui.post(() -> { toast("Неверный пароль"); unlockDialog(then); });
                        } catch (Exception e) {
                            ui.post(() -> toast("Ошибка: " + e.getMessage()));
                        } finally {
                            java.util.Arrays.fill(a, ' ');
                        }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    private void askImportVaultPassword() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(U.dp(this, 20), U.dp(this, 8), U.dp(this, 20), 0);
        final EditText p = field(l, "Мастер-пароль из этого файла", "", true, null);
        new AlertDialog.Builder(this, DIALOG).setTitle("Импорт сейфа").setView(l)
                .setPositiveButton("Импорт", (d, i) -> {
                    final char[] a = chars(p);
                    final byte[] data = pendingVaultImport;
                    bg.execute(() -> {
                        try {
                            final Vault.Opened o = Vault.open(data, a);
                            ui.post(() -> finishVaultImport(data, o));
                        } catch (java.security.GeneralSecurityException e) {
                            ui.post(() -> toast("Неверный пароль или повреждённый файл"));
                        } catch (Exception e) {
                            ui.post(() -> toast("Не файл сейфа"));
                        } finally {
                            java.util.Arrays.fill(a, ' ');
                        }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    private void finishVaultImport(byte[] data, Vault.Opened o) {
        try {
            if (!vault.exists()) {
                vault.adopt(data, o); // новый телефон: берём файл целиком
                toast("Сейф восстановлен: записей " + o.entries.size());
            } else if (vault.unlocked()) {
                toast("Добавлено записей: " + vault.merge(o.entries));
            } else {
                final Runnable again = () -> finishVaultImport(data, o);
                unlockDialog(again);
            }
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void vaultList(final Win target) {
        final List<Vault.Entry> data = new ArrayList<>(vault.entries);
        final String pageHost = target != null && target.web.getUrl() != null ? Store.host(target.web.getUrl()) : "";
        Collections.sort(data, new Comparator<Vault.Entry>() {
            @Override
            public int compare(Vault.Entry a, Vault.Entry b) {
                int sa = (!a.card && Store.hostMatch(pageHost, a.site)) ? 0 : 1;
                int sb = (!b.card && Store.hostMatch(pageHost, b.site)) ? 0 : 1;
                if (sa != sb) return sa - sb;
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        ListView lv = new ListView(this);
        lv.setAdapter(new ArrayAdapter<Vault.Entry>(this, 0, data) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                Vault.Entry e = getItem(pos);
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(U.dp(getContext(), 20), U.dp(getContext(), 10), U.dp(getContext(), 20), U.dp(getContext(), 10));
                row.addView(U.tv(getContext(), (e.card ? "💳 " : "🔑 ") + e.title, 16, WHITE));
                String sub = e.subtitle();
                if (!e.card && !e.site.isEmpty()) sub = e.site + (sub.isEmpty() ? "" : " · " + sub);
                row.addView(U.tv(getContext(), sub, 12, 0x99FFFFFF));
                return row;
            }
        });
        final AlertDialog d = new AlertDialog.Builder(this, DIALOG)
                .setTitle(target == null ? "Сейф" : "Подставить в «" + target.site.name + "»")
                .setView(lv)
                .setPositiveButton("＋ Логин", (x, i) -> {
                    Vault.Entry e = new Vault.Entry();
                    e.site = pageHost.isEmpty() ? "" : Store.bare(pageHost);
                    editEntry(e, true, target);
                })
                .setNeutralButton("＋ Карта", (x, i) -> {
                    Vault.Entry e = new Vault.Entry();
                    e.card = true;
                    editEntry(e, true, target);
                })
                .setNegativeButton("Закрыть", null).create();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            d.dismiss();
            if (target != null) fill(target, data.get(pos));
            else editEntry(data.get(pos), false, null);
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            d.dismiss();
            editEntry(data.get(pos), false, target);
            return true;
        });
        d.show();
    }

    private void fill(final Win w, final Vault.Entry e) {
        if (!e.card && !e.site.isEmpty()) {
            String ph = w.web.getUrl() == null ? "" : Store.host(w.web.getUrl());
            if (!Store.hostMatch(ph, e.site)) {
                new AlertDialog.Builder(this, DIALOG).setTitle("Сайт не совпадает")
                        .setMessage("Запись для «" + e.site + "», а открыт «" + ph + "». Подставить пароль?")
                        .setPositiveButton("Подставить", (d, i) -> doFill(w, e))
                        .setNegativeButton("Отмена", null).show();
                return;
            }
        }
        doFill(w, e);
    }

    private void doFill(Win w, Vault.Entry e) {
        String js = e.card ? Fill.card(e.holder, e.number, e.exp, e.cvv) : Fill.login(e.user, e.pass);
        w.web.evaluateJavascript(js, null);
        toast("Подставлено");
    }

    private void editEntry(final Vault.Entry e, final boolean isNew, final Win target) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(U.dp(this, 20), U.dp(this, 4), U.dp(this, 20), 0);
        final List<EditText> secrets = new ArrayList<>();
        final EditText title = field(l, "Название", e.title, false, secrets);
        final EditText site, user, pass, holder, number, exp, cvv;
        if (e.card) {
            site = user = pass = null;
            holder = field(l, "Имя на карте", e.holder, false, secrets);
            number = field(l, "Номер карты", e.number, false, secrets);
            exp = field(l, "Срок (ММ/ГГ)", e.exp, false, secrets);
            cvv = field(l, "CVV/CVC", e.cvv, true, secrets);
        } else {
            holder = number = exp = cvv = null;
            site = field(l, "Сайт (напр. sberbank.ru)", e.site, false, secrets);
            user = field(l, "Логин", e.user, false, secrets);
            pass = field(l, "Пароль", e.pass, true, secrets);
        }
        final EditText note = field(l, "Заметка", e.note, false, secrets);
        CheckBox cb = new CheckBox(this);
        cb.setText("Показать секретные поля");
        cb.setTextColor(WHITE);
        cb.setOnCheckedChangeListener((v, on) -> {
            for (EditText t : secrets) {
                t.setTransformationMethod(on ? null : PasswordTransformationMethod.getInstance());
            }
        });
        l.addView(cb);
        ScrollView sv = new ScrollView(this);
        sv.addView(l);

        final AlertDialog d = new AlertDialog.Builder(this, DIALOG)
                .setTitle(isNew ? (e.card ? "Новая карта" : "Новый логин") : "Запись")
                .setView(sv)
                .setPositiveButton("Сохранить", null)
                .setNeutralButton("Копировать", null)
                .setNegativeButton(isNew ? "Отмена" : "Удалить", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            e.title = title.getText().toString().trim();
            e.note = note.getText().toString();
            if (e.card) {
                e.holder = holder.getText().toString().trim();
                e.number = number.getText().toString().trim();
                e.exp = exp.getText().toString().trim();
                e.cvv = cvv.getText().toString().trim();
                if (e.title.isEmpty()) e.title = "Карта";
            } else {
                e.site = site.getText().toString().trim();
                e.user = user.getText().toString().trim();
                e.pass = pass.getText().toString();
                if (e.title.isEmpty()) e.title = e.site.isEmpty() ? "Логин" : e.site;
            }
            if (isNew) vault.entries.add(e);
            try {
                vault.save();
            } catch (Exception ex) {
                toast("Не удалось сохранить: " + ex.getMessage());
                return;
            }
            d.dismiss();
            vaultList(target);
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            copySecret(e.card ? number.getText().toString() : pass.getText().toString());
        });
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            d.dismiss();
            if (isNew) { vaultList(target); return; }
            new AlertDialog.Builder(this, DIALOG).setTitle("Удалить запись?")
                    .setPositiveButton("Удалить", (x, i) -> {
                        vault.entries.remove(e);
                        try { vault.save(); } catch (Exception ex) { toast("Ошибка сохранения"); }
                        vaultList(target);
                    })
                    .setNegativeButton("Отмена", (x, i) -> vaultList(target)).show();
        });
    }

    private void copySecret(String text) {
        final ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = ClipData.newPlainText("", text);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle pb = new PersistableBundle();
            pb.putBoolean("android.content.extra.IS_SENSITIVE", true);
            cd.getDescription().setExtras(pb);
        }
        cm.setPrimaryClip(cd);
        toast("Скопировано на 30 секунд");
        ui.postDelayed(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
                else cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Exception ignored) {
            }
        }, 30000);
    }

    // ====================================================================== helpers

    private EditText field(LinearLayout parent, String hint, String val, boolean secret, List<EditText> secrets) {
        EditText t = new EditText(this);
        t.setHint(hint);
        t.setText(val);
        t.setSingleLine(true);
        t.setTextColor(WHITE);
        t.setHintTextColor(0x88FFFFFF);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        if (secret) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        t.setInputType(type);
        if (secret && secrets != null) secrets.add(t);
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private static char[] chars(EditText t) {
        int n = t.length();
        char[] c = new char[n];
        t.getText().getChars(0, n, c, 0);
        return c;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
