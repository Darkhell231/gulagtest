package app.shell;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Зашифрованное хранилище логинов и карт.
 * Формат файла: "PSV1" | alg(1) | iter(4) | salt(16) | iv(12) | AES-256-GCM(json), заголовок (25 байт) идёт как AAD.
 * Ключ = PBKDF2-HMAC-SHA256(мастер-пароль, salt, iter). Ключ хранится только в памяти.
 */
final class Vault {
    static final class Entry {
        String id = UUID.randomUUID().toString();
        boolean card;
        String title = "", site = "", user = "", pass = "";
        String holder = "", number = "", exp = "", cvv = "", note = "";

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("card", card).put("title", title).put("site", site)
                    .put("user", user).put("pass", pass).put("holder", holder).put("number", number)
                    .put("exp", exp).put("cvv", cvv).put("note", note);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optString("id", e.id);
            e.card = o.optBoolean("card");
            e.title = o.optString("title");
            e.site = o.optString("site");
            e.user = o.optString("user");
            e.pass = o.optString("pass");
            e.holder = o.optString("holder");
            e.number = o.optString("number");
            e.exp = o.optString("exp");
            e.cvv = o.optString("cvv");
            e.note = o.optString("note");
            return e;
        }

        String subtitle() {
            if (card) {
                String n = number.replaceAll("\\s", "");
                return n.length() > 4 ? "•••• " + n.substring(n.length() - 4) : "карта";
            }
            return user;
        }

        String dedupKey() {
            return card ? "c:" + number.replaceAll("\\s", "") : "l:" + site.toLowerCase() + "|" + user;
        }
    }

    static final class Opened {
        SecretKeySpec key;
        byte[] salt;
        int iter, alg;
        List<Entry> entries = new ArrayList<>();
    }

    private static final byte[] MAGIC = {'P', 'S', 'V', '1'};
    private static final int HDR = 4 + 1 + 4 + 16; // 25
    private static final int ITER = 150000;
    private static final SecureRandom RNG = new SecureRandom();

    private final File file;
    private SecretKeySpec key;
    private byte[] salt;
    private int iter, alg;
    final List<Entry> entries = new ArrayList<>();

    Vault(Context c) {
        file = new File(c.getFilesDir(), "vault.bin");
    }

    boolean exists() { return file.exists(); }
    boolean unlocked() { return key != null; }

    void lock() {
        key = null;
        salt = null;
        entries.clear();
    }

    static byte[] kdf(char[] pw, byte[] salt, int iter, int alg) throws GeneralSecurityException {
        PBEKeySpec sp = new PBEKeySpec(pw, salt, iter, 256);
        try {
            return SecretKeyFactory.getInstance(alg == 1 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1")
                    .generateSecret(sp).getEncoded();
        } finally {
            sp.clearPassword();
        }
    }

    static int pickAlg() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return 1;
        } catch (GeneralSecurityException e) {
            return 0;
        }
    }

    /** Создать новое хранилище (долгая операция — вызывать не в UI-потоке). */
    void create(char[] pw) throws Exception {
        byte[] s = new byte[16];
        RNG.nextBytes(s);
        int a = pickAlg();
        SecretKeySpec k = new SecretKeySpec(kdf(pw, s, ITER, a), "AES");
        synchronized (this) {
            salt = s;
            alg = a;
            iter = ITER;
            key = k;
            entries.clear();
            save();
        }
    }

    /** Расшифровать данные (долгая операция). Неверный пароль → GeneralSecurityException. */
    static Opened open(byte[] data, char[] pw) throws GeneralSecurityException, JSONException, IOException {
        if (data.length < HDR + 12 + 16 || !Arrays.equals(Arrays.copyOf(data, 4), MAGIC)) {
            throw new IOException("bad format");
        }
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.position(4);
        Opened o = new Opened();
        o.alg = bb.get();
        o.iter = bb.getInt();
        if (o.iter < 1000 || o.iter > 5000000) throw new IOException("bad params");
        o.salt = new byte[16];
        bb.get(o.salt);
        byte[] iv = new byte[12];
        bb.get(iv);
        o.key = new SecretKeySpec(kdf(pw, o.salt, o.iter, o.alg), "AES");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, o.key, new GCMParameterSpec(128, iv));
        c.updateAAD(data, 0, HDR);
        byte[] plain = c.doFinal(data, HDR + 12, data.length - HDR - 12);
        JSONArray a = new JSONObject(new String(plain, StandardCharsets.UTF_8)).getJSONArray("entries");
        for (int i = 0; i < a.length(); i++) o.entries.add(Entry.from(a.getJSONObject(i)));
        Arrays.fill(plain, (byte) 0);
        return o;
    }

    void unlock(char[] pw) throws Exception {
        apply(open(Io.readFile(file), pw));
    }

    /** Принять расшифрованное хранилище (из unlock или при восстановлении резервной копии). */
    synchronized void apply(Opened o) {
        key = o.key;
        salt = o.salt;
        iter = o.iter;
        alg = o.alg;
        entries.clear();
        entries.addAll(o.entries);
    }

    /** Заменить файл хранилища резервной копией (после успешной проверки пароля). */
    synchronized void adopt(byte[] data, Opened o) throws IOException {
        Io.writeAtomic(file, data);
        apply(o);
    }

    synchronized void save() throws Exception {
        if (key == null) throw new IllegalStateException("locked");
        JSONArray a = new JSONArray();
        for (Entry e : entries) a.put(e.json());
        byte[] plain = new JSONObject().put("v", 1).put("entries", a).toString().getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        ByteBuffer hdr = ByteBuffer.allocate(HDR);
        hdr.put(MAGIC).put((byte) alg).putInt(iter).put(salt);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD(hdr.array());
        byte[] ct = c.doFinal(plain);
        Arrays.fill(plain, (byte) 0);
        ByteBuffer out = ByteBuffer.allocate(HDR + 12 + ct.length);
        out.put(hdr.array()).put(iv).put(ct);
        Io.writeAtomic(file, out.array());
    }

    byte[] raw() throws IOException {
        return Io.readFile(file);
    }

    /** Добавить записи из резервной копии, пропуская дубликаты. Возвращает число добавленных. */
    synchronized int merge(List<Entry> in) throws Exception {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Entry e : entries) have.add(e.dedupKey());
        int n = 0;
        for (Entry e : in) {
            if (have.add(e.dedupKey())) {
                entries.add(e);
                n++;
            }
        }
        if (n > 0) save();
        return n;
    }
}
