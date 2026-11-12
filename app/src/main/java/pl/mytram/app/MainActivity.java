package pl.mytram.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView web;
    private Engine engine;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new Engine(getFilesDir());

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E1117"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // tu zapisują się ustawienia
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Most(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        Reminder.createChannel(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.onAppPause && window.onAppPause()", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", wynik -> {
            if (!"true".equals(wynik)) MainActivity.super.onBackPressed();
        });
    }

    private void reply(String id, boolean ok, String payload) {
        final String js = "window.__res(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload == null ? "" : payload) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /** Most JS -> Android. */
    private class Most {

        /** Zapytanie asynchroniczne: odpowiedź przychodzi do window.__res(id, ok, tekst). */
        @JavascriptInterface
        public void call(final String id, final String method, final String arg) {
            pool.execute(() -> {
                try {
                    String r;
                    switch (method) {
                        case "init":
                            engine.ensureGtfs(false);
                            r = engine.gtfsInfoJson();
                            break;
                        case "updateGtfs":
                            engine.ensureGtfs(true);
                            r = engine.gtfsInfoJson();
                            break;
                        case "lines":
                            engine.ensureGtfs(false);
                            r = engine.linesJson();
                            break;
                        case "search":
                            engine.ensureGtfs(false);
                            r = engine.searchJson(arg);
                            break;
                        case "weather":
                            r = engine.weatherJson();
                            break;
                        case "dashboard": {
                            JSONObject o = new JSONObject(arg);
                            Set<String> lines = new HashSet<>();
                            JSONArray a = o.optJSONArray("lines");
                            if (a != null) for (int i = 0; i < a.length(); i++) lines.add(a.getString(i));
                            r = engine.dashboard(o.getString("from"), o.getString("to"), lines, o.optInt("count", 3));
                            break;
                        }
                        default:
                            throw new IllegalArgumentException("nieznana metoda " + method);
                    }
                    reply(id, true, r);
                } catch (Throwable e) {
                    String msg = e.getMessage();
                    reply(id, false, e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
                }
            });
        }

        @JavascriptInterface
        public void ustawMotyw(final boolean ciemny) {
            runOnUiThread(() -> {
                int kolor = Color.parseColor(ciemny ? "#0E1117" : "#F2F4F7");
                getWindow().setStatusBarColor(kolor);
                getWindow().setNavigationBarColor(kolor);
                web.setBackgroundColor(kolor);
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController c = getWindow().getInsetsController();
                    if (c != null) {
                        int flagi = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                        c.setSystemBarsAppearance(ciemny ? 0 : flagi, flagi);
                    }
                } else {
                    View d = getWindow().getDecorView();
                    int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    d.setSystemUiVisibility(ciemny ? 0 : f);
                }
            });
        }

        /** Przypomnienie o końcu migawki (czas w ms). 0 = anuluj. */
        @JavascriptInterface
        public void ustawPrzypomnienie(final double kiedyMs, final String tekst) {
            Context ctx = MainActivity.this;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, Reminder.class);
            i.putExtra("tekst", tekst);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            long kiedy = (long) kiedyMs;
            if (kiedy > System.currentTimeMillis()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, kiedy, pi);
            }
        }

        /** Prośba o zgodę na powiadomienia (Android 13+). */
        @JavascriptInterface
        public void zgodaNaPowiadomienia() {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7));
            }
        }
    }
}
