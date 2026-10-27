package app.shell;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * Корневые сертификаты, действующие только внутри приложения.
 *  - встроенные: res/raw (+ network_security_config, работает для всех запросов WebView);
 *  - импортированные пользователем: filesDir/certs (проверяются вручную при ошибке SSL главной страницы).
 */
final class Certs {
    static final String[] BUNDLED = {
            "russian_trusted_root_ca", "russian_trusted_sub_ca", "russian_trusted_sub_ca_2024"};

    static final class Item {
        X509Certificate cert;
        boolean bundled;
        File file;

        String name() {
            String dn = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
            Matcher m = Pattern.compile("CN=([^,]+)").matcher(dn);
            return m.find() ? m.group(1) : dn;
        }

        String fingerprint() {
            try {
                String h = Io.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
                return h.substring(0, 16);
            } catch (Exception e) {
                return "?";
            }
        }
    }

    private final Context ctx;
    private final File dir;

    Certs(Context c) {
        ctx = c;
        dir = new File(c.getFilesDir(), "certs");
        dir.mkdirs();
    }

    static List<X509Certificate> parse(byte[] data) throws Exception {
        List<X509Certificate> out = new ArrayList<>();
        Collection<? extends Certificate> cs = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(data));
        for (Certificate c : cs) if (c instanceof X509Certificate) out.add((X509Certificate) c);
        return out;
    }

    List<Item> list() {
        List<Item> out = new ArrayList<>();
        for (String n : BUNDLED) {
            int id = ctx.getResources().getIdentifier(n, "raw", ctx.getPackageName());
            if (id == 0) continue;
            try {
                for (X509Certificate c : parse(Io.readAll(ctx.getResources().openRawResource(id), 100000))) {
                    Item it = new Item();
                    it.cert = c;
                    it.bundled = true;
                    out.add(it);
                }
            } catch (Exception ignored) {
            }
        }
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                try {
                    for (X509Certificate c : parse(Io.readFile(f))) {
                        Item it = new Item();
                        it.cert = c;
                        it.file = f;
                        out.add(it);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** Возвращает число добавленных сертификатов. Бросает исключение, если файл не сертификат. */
    int importBytes(byte[] data) throws Exception {
        List<X509Certificate> cs = parse(data);
        int n = 0;
        for (X509Certificate c : cs) {
            String h = Io.hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
            File f = new File(dir, h.substring(0, 32) + ".der");
            if (!f.exists()) {
                Io.writeAtomic(f, c.getEncoded());
                n++;
            }
        }
        if (cs.isEmpty()) throw new IllegalArgumentException("no certificates");
        return n;
    }

    void delete(Item it) {
        if (it.file != null) it.file.delete();
    }

    /**
     * Проверка сервера по нашим сертификатам: открываем TLS-соединение сами, берём цепочку,
     * убеждаемся, что листовой сертификат тот же, что видел WebView, имя хоста совпадает,
     * и цепочка приходит к одному из наших сертификатов.
     */
    boolean verifyHost(String host, int port, X509Certificate leaf) {
        try {
            List<X509Certificate> anchors = new ArrayList<>();
            for (Item it : list()) anchors.add(it.cert);
            if (anchors.isEmpty()) return false;

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 8000);
            raw.setSoTimeout(8000);
            SSLSocket s = (SSLSocket) sc.getSocketFactory().createSocket(raw, host, port, true);
            try {
                s.startHandshake();
                SSLSession ses = s.getSession();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ses)) return false;
                Certificate[] pc = ses.getPeerCertificates();
                X509Certificate[] chain = new X509Certificate[pc.length];
                for (int i = 0; i < pc.length; i++) chain[i] = (X509Certificate) pc[i];
                if (chain.length == 0 || !Arrays.equals(chain[0].getEncoded(), leaf.getEncoded())) return false;
                return chainTrusted(chain, anchors);
            } finally {
                try { s.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean chainTrusted(X509Certificate[] chain, List<X509Certificate> anchors) {
        int i = 0;
        while (i < chain.length) {
            X509Certificate cur = chain[i];
            try {
                cur.checkValidity();
            } catch (Exception e) {
                return false;
            }
            for (X509Certificate a : anchors) {
                if (a.equals(cur)) return true;
                if (a.getBasicConstraints() >= 0
                        && cur.getIssuerX500Principal().equals(a.getSubjectX500Principal())) {
                    try {
                        a.checkValidity();
                        cur.verify(a.getPublicKey());
                        return true;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (i + 1 >= chain.length) return false;
            X509Certificate next = chain[i + 1];
            if (next.getBasicConstraints() < 0) return false;
            try {
                cur.verify(next.getPublicKey());
            } catch (Exception e) {
                return false;
            }
            i++;
        }
        return false;
    }
}
