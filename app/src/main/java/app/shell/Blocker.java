package app.shell;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Блокировщик рекламы по доменам. Список: небольшой встроенный + assets/blocklist.txt
 * (домены из правил вида ||domain^ в EasyList, RuAdList, uAssets — формирует get_lists.sh при сборке).
 * Расширение uBlock Origin в WebView работать не может, это его замена для основных рекламных сетей.
 */
final class Blocker {
    private Blocker() {}

    private static final String[] BUILTIN = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
            "an.yandex.ru", "yandexadexchange.net", "adfox.ru", "ad.mail.ru", "moatads.com",
            "pubmatic.com", "rubiconproject.com", "openx.net", "smartadserver.com", "adriver.ru"};

    static final String HIDE_JS = "(function(){var s=document.createElement('style');"
            + "s.textContent='ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_\"],[id^=\"div-gpt-ad\"],"
            + "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],"
            + "[id^=\"yandex_rtb\"],[class*=\"yandex_rtb\"],[id^=\"adfox_\"]{display:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();";

    private static volatile Set<String> domains = new HashSet<>(Arrays.asList(BUILTIN));

    static void load(Context c) {
        Set<String> s = new HashSet<>(Arrays.asList(BUILTIN));
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getAssets().open("blocklist.txt"), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (!l.isEmpty() && l.charAt(0) != '#') s.add(l.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
        }
        domains = s;
    }

    static boolean blocked(String host) {
        Set<String> d = domains;
        String h = host.toLowerCase(Locale.ROOT);
        while (h.indexOf('.') >= 0) {
            if (d.contains(h)) return true;
            h = h.substring(h.indexOf('.') + 1);
        }
        return false;
    }
}
