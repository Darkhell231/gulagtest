package app.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.MathContext;

/** Рабочий калькулятор-маска. Код (4–12 цифр) + «=» открывает настоящее приложение. */
final class Calc extends LinearLayout {
    interface Check { boolean accept(String digits); }

    private static final String OPS = "+−×÷";
    private final Check check;
    private final Runnable onOpen;
    private final StringBuilder ex = new StringBuilder();
    private final TextView tExpr, tRes;
    private String s;
    private int p;

    Calc(Context c, Check check, Runnable onOpen) {
        super(c);
        this.check = check;
        this.onOpen = onOpen;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF14181D);
        setClickable(true);

        LinearLayout disp = new LinearLayout(c);
        disp.setOrientation(VERTICAL);
        disp.setGravity(Gravity.BOTTOM | Gravity.END);
        disp.setPadding(U.dp(c, 24), U.dp(c, 16), U.dp(c, 24), U.dp(c, 16));
        tExpr = U.tv(c, "", 22, 0xFF9AA5B1);
        tExpr.setSingleLine(true);
        tExpr.setGravity(Gravity.END);
        tRes = U.tv(c, "0", 52, 0xFFFFFFFF);
        tRes.setSingleLine(true);
        tRes.setGravity(Gravity.END);
        disp.addView(tExpr, new LayoutParams(-1, -2));
        disp.addView(tRes, new LayoutParams(-1, -2));
        addView(disp, new LayoutParams(-1, 0, 2f));

        String[][] rows = {
                {"C", "⌫", "%", "÷"},
                {"7", "8", "9", "×"},
                {"4", "5", "6", "−"},
                {"1", "2", "3", "+"},
                {"±", "0", ".", "="}};
        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(VERTICAL);
        pad.setPadding(U.dp(c, 8), 0, U.dp(c, 8), U.dp(c, 8));
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(c);
            for (final String k : r) {
                TextView b = U.center(U.tv(c, k, 24, 0xFFFFFFFF));
                int bg = 0xFF262C33, fg = 0xFFFFFFFF;
                if (OPS.contains(k) || k.equals("%")) { bg = 0xFF2F4B6E; fg = 0xFFBFD9FF; }
                if (k.equals("C") || k.equals("⌫")) { bg = 0xFF3B2F33; fg = 0xFFFFB4AB; }
                if (k.equals("=")) { bg = 0xFF4C8DFF; fg = 0xFFFFFFFF; }
                b.setBackground(U.box(c, bg, 14));
                b.setTextColor(fg);
                b.setOnClickListener(v -> key(k));
                LayoutParams lp = new LayoutParams(0, -1, 1f);
                lp.setMargins(U.dp(c, 4), U.dp(c, 4), U.dp(c, 4), U.dp(c, 4));
                row.addView(b, lp);
            }
            pad.addView(row, new LayoutParams(-1, 0, 1f));
        }
        addView(pad, new LayoutParams(-1, 0, 4f));
    }

    void reset() {
        ex.setLength(0);
        update();
    }

    private void key(String k) {
        int n = ex.length();
        char last = n > 0 ? ex.charAt(n - 1) : ' ';
        switch (k) {
            case "C": ex.setLength(0); break;
            case "⌫": if (n > 0) ex.setLength(n - 1); break;
            case "=": equalsKey(); return;
            case "±": negate(); break;
            case "%": if (Character.isDigit(last)) ex.append('%'); break;
            case ".": {
                int i = n;
                while (i > 0 && (Character.isDigit(ex.charAt(i - 1)) || ex.charAt(i - 1) == '.')) i--;
                if (!ex.substring(i).contains(".")) {
                    if (i == n) ex.append('0');
                    ex.append('.');
                }
                break;
            }
            case "+": case "−": case "×": case "÷": {
                if (n == 0) {
                    if (k.equals("−")) ex.append(k);
                } else if (OPS.indexOf(last) >= 0) {
                    if (n > 1) ex.setCharAt(n - 1, k.charAt(0));
                } else {
                    ex.append(k);
                }
                break;
            }
            default:
                if (n < 40) ex.append(k);
        }
        update();
    }

    private void negate() {
        int n = ex.length();
        if (n == 0 || OPS.indexOf(ex.charAt(n - 1)) >= 0) return;
        int i = n;
        while (i > 0 && (Character.isDigit(ex.charAt(i - 1)) || ex.charAt(i - 1) == '.' || ex.charAt(i - 1) == '%')) i--;
        if (i > 0 && ex.charAt(i - 1) == '−' && (i - 1 == 0 || OPS.indexOf(ex.charAt(i - 2)) >= 0)) {
            ex.deleteCharAt(i - 1);
        } else {
            ex.insert(i, '−');
        }
    }

    private void equalsKey() {
        String cur = ex.toString();
        if (cur.matches("\\d{4,12}") && check.accept(cur)) {
            reset();
            onOpen.run();
            return;
        }
        try {
            String r = fmt(eval(cur));
            tExpr.setText(cur);
            ex.setLength(0);
            ex.append(r);
            tRes.setText(r);
        } catch (ArithmeticException e) {
            ex.setLength(0);
            tExpr.setText("");
            tRes.setText("Ошибка");
        } catch (Exception ignored) {
        }
    }

    private void update() {
        tExpr.setText(ex.toString());
        String r = "0";
        if (ex.length() > 0) {
            try { r = fmt(eval(ex.toString())); } catch (Exception e) { r = ""; }
        }
        tRes.setText(r);
        tRes.setTextSize(r.length() > 12 ? 30 : r.length() > 9 ? 38 : 52);
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new ArithmeticException();
        String r = new BigDecimal(v).round(new MathContext(12)).stripTrailingZeros().toPlainString();
        return r.replace('-', '−');
    }

    // ---- разбор выражения: + − × ÷ %, унарный минус ----
    private double eval(String str) {
        s = str;
        p = 0;
        double v = expr();
        if (p < s.length()) throw new IllegalArgumentException();
        return v;
    }

    private double expr() {
        double v = term();
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '+') { p++; v += term(); }
            else if (c == '−') { p++; v -= term(); }
            else break;
        }
        return v;
    }

    private double term() {
        double v = factor();
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '×') { p++; v *= factor(); }
            else if (c == '÷') {
                p++;
                double d = factor();
                if (d == 0) throw new ArithmeticException();
                v /= d;
            } else break;
        }
        return v;
    }

    private double factor() {
        boolean neg = false;
        while (p < s.length() && s.charAt(p) == '−') { neg = !neg; p++; }
        int st = p;
        while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
        if (st == p) throw new IllegalArgumentException();
        double v = Double.parseDouble(s.substring(st, p));
        while (p < s.length() && s.charAt(p) == '%') { v /= 100; p++; }
        return neg ? -v : v;
    }
}
