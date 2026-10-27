package app.shell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class Io {
    private Io() {}

    static byte[] readAll(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                if (o.size() > max) break;
            }
            return o.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    static byte[] readFile(File f) throws IOException {
        return readAll(new FileInputStream(f), 64 << 20);
    }

    static void writeAtomic(File f, byte[] data) throws IOException {
        File t = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(t);
        try {
            o.write(data);
            o.getFD().sync();
        } finally {
            o.close();
        }
        if (!t.renameTo(f)) {
            f.delete();
            if (!t.renameTo(f)) throw new IOException("rename failed");
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
