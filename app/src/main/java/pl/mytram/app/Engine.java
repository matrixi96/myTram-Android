package pl.mytram.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cała logika danych myTram - bez zależności od Androida (da się testować na komputerze). */
final class Engine {
    static final String BASE = "https://otwarte.miasto.lodz.pl/wp-content/uploads/2025/06/";
    static final String GTFS_URL = BASE + "GTFS.zip";
    static final String TRIPS_URL = BASE + "trip_updates.bin";
    static final String VEH_URL = BASE + "vehicle_positions.bin";
    static final String ALERTS_URL = BASE + "alerts.bin";
    static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=51.7592&longitude=19.4560"
            + "&current=temperature_2m,apparent_temperature,precipitation,rain,showers,snowfall,weather_code,"
            + "wind_speed_10m,wind_direction_10m,wind_gusts_10m&timezone=Europe%2FWarsaw&wind_speed_unit=kmh";

    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final long GTFS_MAX_AGE_MS = 24L * 3600 * 1000; // rozkład odświeżany raz na dobę

    private final File dir;
    private volatile Gtfs gtfs;

    // postęp pobierania / przygotowania rozkładu (czytany bez blokady)
    volatile String stage = "";
    volatile long pDone, pTotal;
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean(false);

    String progressJson() {
        return "{\"stage\":\"" + stage + "\",\"done\":" + pDone + ",\"total\":" + pTotal + ",\"busy\":" + busy.get() + ",\"loaded\":" + (gtfs != null) + "}";
    }

    private void prog(String st, long done, long total) { stage = st; pDone = done; pTotal = total; }
    private long gtfsLoadedFrom;

    Engine(File dir) { this.dir = dir; }

    // ------------------------------------------------------------------ rozkład

    /** Wczytuje rozkład (z pamięci, z dysku albo pobiera). Zwraca opis stanu. */
    String ensureGtfs(boolean force) throws IOException {
        if (force && busy.get()) return "busy"; // już pobieram - nie zaczynaj drugi raz
        synchronized (this) {
            busy.set(true);
            try {
                return ensureGtfsLocked(force);
            } finally {
                busy.set(false);
                prog("", 0, 0);
            }
        }
    }

    private String ensureGtfsLocked(boolean force) throws IOException {
        File zip = new File(dir, "GTFS.zip");
        File cache = new File(dir, "gtfs.bin");
        boolean stale = !zip.exists() || System.currentTimeMillis() - zip.lastModified() > GTFS_MAX_AGE_MS;
        if (force || stale) {
            try {
                File tmp = new File(dir, "GTFS.zip.part");
                prog("download", 0, 0);
                download(GTFS_URL, tmp, this);
                if (zip.exists()) zip.delete();
                if (!tmp.renameTo(zip)) throw new IOException("zapis rozkładu");
                cache.delete();
            } catch (IOException e) {
                if (!zip.exists() && !cache.exists()) throw e; // nie mamy nic - błąd
            }
        }
        if (gtfs != null && cache.exists() && gtfsLoadedFrom == cache.lastModified()) return "ok";
        if (cache.exists() && (!zip.exists() || cache.lastModified() >= zip.lastModified())) {
            try {
                prog("load", 0, 0);
                gtfs = Gtfs.load(cache);
                gtfsLoadedFrom = cache.lastModified();
                return "ok";
            } catch (IOException ignored) {
                cache.delete();
            }
        }
        prog("parse", 0, 0);
        Gtfs g = Gtfs.parseZip(zip, this::prog);
        prog("save", 0, 0);
        gtfs = g;
        try {
            g.save(cache);
            gtfsLoadedFrom = cache.lastModified();
        } catch (IOException ignored) { }
        return "ok";
    }

    Gtfs gtfs() { return gtfs; }

    String linesJson() {
        StringBuilder sb = new StringBuilder("[");
        if (gtfs != null) for (int i = 0; i < gtfs.lines.length; i++) {
            if (i > 0) sb.append(',');
            Json.str(sb, gtfs.lines[i]);
        }
        return sb.append(']').toString();
    }

    String searchJson(String q) {
        StringBuilder sb = new StringBuilder("[");
        if (gtfs != null) {
            List<String[]> r = gtfs.searchStops(q, 25);
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"name\":"); Json.str(sb, r.get(i)[0]);
                sb.append(",\"lines\":"); Json.str(sb, r.get(i)[1]); sb.append('}');
            }
        }
        return sb.append(']').toString();
    }

    String gtfsInfoJson() {
        File zip = new File(dir, "GTFS.zip");
        StringBuilder sb = new StringBuilder("{\"loaded\":").append(gtfs != null);
        sb.append(",\"updated\":").append(zip.exists() ? zip.lastModified() : 0);
        sb.append(",\"trips\":").append(gtfs == null ? 0 : gtfs.nTrips).append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------------ pogoda

    String weatherJson() throws IOException {
        return new String(get(WEATHER_URL), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ odjazdy

    static final class Cand {
        int t, i, j;
        long schedDep, schedArr;
        long estDep, estArr;
        int delay;
        boolean realtime;
        Rt.Veh veh;
        int away = -99; // przystanki do mojego przystanku (gdy znana pozycja)
    }

    /** Główny ekran: odjazdy z → do, opóźnienia, pozycje, komunikaty. */
    String dashboard(String fromName, String toName, Set<String> lines, int count) throws IOException {
        ensureGtfs(false);
        Gtfs g = gtfs;
        long now = System.currentTimeMillis() / 1000;
        StringBuilder sb = new StringBuilder("{");

        String fromCanon = g.canonicalName(fromName), toCanon = g.canonicalName(toName);
        sb.append("\"fromFound\":").append(fromCanon != null);
        sb.append(",\"toFound\":").append(toCanon != null);
        if (fromCanon == null || toCanon == null) {
            return sb.append(",\"deps\":[],\"alerts\":[],\"rtOk\":false}").toString();
        }

        // 1. kursy z rozkładu
        boolean[] fromSet = g.stopsNamed(fromCanon), toSet = g.stopsNamed(toCanon);
        List<Cand> cands = new ArrayList<>();
        LocalDate today = Instant.ofEpochSecond(now).atZone(ZONE).toLocalDate();
        Set<Integer> segmentStops = new HashSet<>();
        for (int dayOff = -1; dayOff <= 0; dayOff++) {
            LocalDate d = today.plusDays(dayOff);
            long base = d.atStartOfDay(ZONE).toEpochSecond();
            for (int t = 0; t < g.nTrips; t++) {
                if (!lines.isEmpty() && !lines.contains(g.tripLine[t])) continue;
                int[] st = g.tStop[t];
                int i = -1, j = -1;
                for (int k = 0; k < st.length; k++) {
                    if (i < 0) { if (fromSet[st[k]]) i = k; }
                    else if (toSet[st[k]]) { j = k; break; }
                }
                if (i < 0 || j < 0) continue;
                for (int k = i; k <= j; k++) segmentStops.add(st[k]);
                long dep = base + g.tDep[t][i];
                if (dep < now - 45 * 60 || dep > now + 4 * 3600) continue;
                if (!g.serviceActive(g.tripService[t], d)) continue;
                Cand c = new Cand();
                c.t = t; c.i = i; c.j = j;
                c.schedDep = dep;
                c.schedArr = base + g.tArr[t][j];
                cands.add(c);
            }
        }

        // 2. dane na żywo
        Rt rt = new Rt();
        boolean rtOk = true;
        try { rt.parseTripUpdates(get(TRIPS_URL)); } catch (Exception e) { rtOk = false; }
        try { rt.parseVehicles(get(VEH_URL)); } catch (Exception e) { rtOk = false; }
        try { rt.parseAlerts(get(ALERTS_URL)); } catch (Exception ignored) { }

        List<Cand> out = new ArrayList<>();
        for (Cand c : cands) {
            String tid = g.tripId[c.t];
            Rt.TripRt tr = rt.trips.get(tid);
            if (tr != null && tr.canceled) continue;
            Integer dDep = delayAt(g, tr, c.t, c.i, c.schedDep - g.tDep[c.t][c.i]);
            Integer dArr = delayAt(g, tr, c.t, c.j, c.schedDep - g.tDep[c.t][c.i]);
            c.realtime = dDep != null;
            c.delay = dDep == null ? 0 : dDep;
            c.estDep = c.schedDep + c.delay;
            c.estArr = c.schedArr + (dArr != null ? dArr : c.delay);
            c.veh = rt.vehicles.get(tid);
            if (c.veh != null) {
                int k = vehicleIndex(g, c.t, c.veh);
                if (k >= 0) c.away = c.i - k;
                if (c.away < 0 && c.away != -99) continue; // już przejechał mój przystanek
            }
            if (c.estDep < now - 30) continue;
            out.add(c);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.estDep, b.estDep));
        if (out.size() > count) out = out.subList(0, count);

        // 3. wynik
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        sb.append(",\"from\":"); Json.str(sb, fromCanon);
        sb.append(",\"to\":"); Json.str(sb, toCanon);
        sb.append(",\"rtOk\":").append(rtOk);
        sb.append(",\"now\":").append(now);
        sb.append(",\"deps\":[");
        boolean bigDelay = false;
        for (int n = 0; n < out.size(); n++) {
            Cand c = out.get(n);
            if (n > 0) sb.append(',');
            if (c.delay >= 600) bigDelay = true;
            sb.append("{\"line\":"); Json.str(sb, g.tripLine[c.t]);
            sb.append(",\"headsign\":"); Json.str(sb, g.tripHeadsign[c.t]);
            sb.append(",\"schedDep\":"); Json.str(sb, hm.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(c.schedDep), ZONE)));
            sb.append(",\"estDep\":").append(c.estDep);
            sb.append(",\"estArr\":").append(c.estArr);
            sb.append(",\"arr\":"); Json.str(sb, hm.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(c.estArr), ZONE)));
            sb.append(",\"rideMin\":").append(Math.max(1, Math.round((c.estArr - c.estDep) / 60.0)));
            sb.append(",\"delay\":").append(c.delay);
            sb.append(",\"realtime\":").append(c.realtime);
            sb.append(",\"hasVeh\":").append(c.veh != null && c.away != -99);
            sb.append(",\"away\":").append(c.away);
            sb.append(",\"stopped\":").append(c.veh != null && c.veh.status == 1);
            sb.append('}');
        }
        sb.append("]");
        sb.append(",\"bigDelay\":").append(bigDelay);

        // pasek "gdzie są tramwaje": 3 poprzednie przystanki + mój
        sb.append(",\"track\":[");
        if (!out.isEmpty()) {
            Cand f = out.get(0);
            int[] st = g.tStop[f.t];
            for (int k = 3; k >= 1; k--) {
                int idx = f.i - k;
                Json.str(sb, idx >= 0 ? g.stopName[st[idx]] : "");
                sb.append(',');
            }
            Json.str(sb, fromCanon);
        }
        sb.append("]");

        // komunikaty dotyczące moich linii albo przystanków na trasie
        Set<String> segIds = new HashSet<>();
        for (int s : segmentStops) segIds.add(g.stopId[s]);
        sb.append(",\"alerts\":[");
        boolean first = true;
        for (Rt.Alert a : rt.alerts) {
            if (!a.activeAt(now)) continue;
            boolean match = a.routes.isEmpty() && a.stops.isEmpty();
            for (String r : a.routes) {
                String ln = g.routeShort.containsKey(r) ? g.routeShort.get(r) : r;
                if (lines.contains(ln) || lines.contains(r)) match = true;
            }
            for (String s : a.stops) if (segIds.contains(s)) match = true;
            if (!match) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"title\":"); Json.str(sb, a.header);
            sb.append(",\"text\":"); Json.str(sb, a.text);
            sb.append(",\"lines\":[");
            for (int k = 0; k < a.routes.size(); k++) {
                if (k > 0) sb.append(',');
                String r = a.routes.get(k);
                Json.str(sb, g.routeShort.containsKey(r) ? g.routeShort.get(r) : r);
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Opóźnienie (s) na przystanku k kursu t, albo null gdy brak danych na żywo. */
    private static Integer delayAt(Gtfs g, Rt.TripRt tr, int t, int k, long dayBase) {
        if (tr == null) return null;
        int[] st = g.tStop[t], seq = g.tSeq[t];
        Rt.Stu best = null;
        int bestPos = -1;
        Rt.Stu firstAfter = null;
        int firstAfterPos = Integer.MAX_VALUE;
        for (Rt.Stu s : tr.stus) {
            int p = -1;
            if (!s.stopId.isEmpty()) {
                for (int x = 0; x < st.length; x++) if (g.stopId[st[x]].equals(s.stopId)) { p = x; break; }
            }
            if (p < 0 && s.seq >= 0) {
                for (int x = 0; x < seq.length; x++) if (seq[x] == s.seq) { p = x; break; }
            }
            if (p < 0) continue;
            if (!s.hasDelay && s.time <= 0) continue;
            if (p <= k && p > bestPos) { best = s; bestPos = p; }
            if (p > k && p < firstAfterPos) { firstAfter = s; firstAfterPos = p; }
        }
        Rt.Stu use = best != null ? best : firstAfter;
        int pos = best != null ? bestPos : firstAfterPos;
        if (use != null) {
            if (use.hasDelay) return use.delay;
            int sched = pos == k ? g.tDep[t][k] : g.tDep[t][pos];
            return (int) (use.time - (dayBase + sched));
        }
        if (tr.hasDelay) return tr.delay;
        return null;
    }

    /** Indeks przystanku w kursie, przy którym jest (albo do którego jedzie) tramwaj. */
    private static int vehicleIndex(Gtfs g, int t, Rt.Veh v) {
        int[] st = g.tStop[t], seq = g.tSeq[t];
        if (!v.stopId.isEmpty()) for (int x = 0; x < st.length; x++) if (g.stopId[st[x]].equals(v.stopId)) return x;
        if (v.seq >= 0) for (int x = 0; x < seq.length; x++) if (seq[x] == v.seq) return x;
        if (v.hasPos) {
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int x = 0; x < st.length; x++) {
                double dy = g.lat[st[x]] - v.lat, dx = (g.lon[st[x]] - v.lon) * 0.62;
                double d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = x; }
            }
            return best;
        }
        return -1;
    }

    // ------------------------------------------------------------------ sieć

    interface Fetcher { byte[] get(String url) throws IOException; }
    static Fetcher fetcher = null; // do testów

    static byte[] get(String url) throws IOException {
        if (fetcher != null) return fetcher.get(url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "myTram/1.0 (Android)");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static void download(String url, File to) throws IOException { download(url, to, null); }

    static void download(String url, File to, Engine e) throws IOException {
        if (fetcher != null) { try (OutputStream o = new FileOutputStream(to)) { o.write(fetcher.get(url)); } return; }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "myTram/1.1 (Android)");
        long deadline = System.currentTimeMillis() + 6 * 60 * 1000;
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("serwer MPK odpowiedział HTTP " + code);
            long total = c.getContentLengthLong();
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (e != null) e.prog("download", done, total);
                    if (System.currentTimeMillis() > deadline) throw new IOException("pobieranie trwa za długo (serwer MPK odpowiada bardzo wolno)");
                }
            }
            if (total > 0 && done < total) throw new IOException("pobieranie przerwane");
        } finally {
            c.disconnect();
        }
    }

    static final class Json {
        static void str(StringBuilder sb, String s) {
            sb.append('"');
            if (s != null) for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': break;
                    case '\t': sb.append(' '); break;
                    default:
                        if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                }
            }
            sb.append('"');
        }
    }
}
