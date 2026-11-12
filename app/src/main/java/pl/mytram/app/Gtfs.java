package pl.mytram.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Rozkład jazdy MPK Łódź (GTFS) - tylko tramwaje, w zwartej postaci. */
final class Gtfs {
    private static final int CACHE_VERSION = 3;

    // przystanki
    String[] stopId, stopName;
    double[] lat, lon;
    final HashMap<String, Integer> stopIdx = new HashMap<>();

    // linie: route_id -> numer linii
    final HashMap<String, String> routeShort = new HashMap<>();

    // kursy
    int nTrips;
    String[] tripId, tripLine, tripService, tripHeadsign;
    int[][] tStop, tSeq, tArr, tDep;
    final HashMap<String, Integer> tripIdx = new HashMap<>();

    // kalendarz
    final HashMap<String, int[]> cal = new HashMap<>();          // service -> [maska pn..nd, od, do]
    final HashMap<String, Integer> calDates = new HashMap<>();    // service|yyyymmdd -> 1 dodany / 2 usunięty

    // pomocnicze (liczone po wczytaniu)
    String[] lines = new String[0];
    final HashMap<String, TreeSet<String>> linesAtName = new HashMap<>();

    // ------------------------------------------------------------------ parsowanie GTFS.zip

    interface Prog { void set(String stage, long done, long total); }

    static Gtfs parseZip(File zipFile) throws IOException { return parseZip(zipFile, null); }

    static Gtfs parseZip(File zipFile, Prog prog) throws IOException {
        Gtfs g = new Gtfs();
        try (ZipFile z = new ZipFile(zipFile)) {
            // routes.txt - tylko tramwaje (route_type 0 lub 900-999)
            HashMap<String, String> trams = new HashMap<>();
            HashMap<String, String> all = new HashMap<>();
            try (Csv c = Csv.open(z, "routes.txt")) {
                int iId = c.col("route_id"), iShort = c.col("route_short_name"), iLong = c.col("route_long_name"), iType = c.col("route_type");
                String[] r;
                while ((r = c.row()) != null) {
                    String id = c.get(r, iId);
                    String nm = c.get(r, iShort);
                    if (nm.isEmpty()) nm = c.get(r, iLong);
                    if (nm.isEmpty()) nm = id;
                    int type = parseIntSafe(c.get(r, iType), 3);
                    all.put(id, nm);
                    if (type == 0 || (type >= 900 && type < 1000)) trams.put(id, nm);
                }
            }
            HashMap<String, String> use = trams.isEmpty() ? all : trams;
            g.routeShort.putAll(all);

            // stops.txt
            ArrayList<String> sId = new ArrayList<>(), sName = new ArrayList<>();
            ArrayList<double[]> sPos = new ArrayList<>();
            try (Csv c = Csv.open(z, "stops.txt")) {
                int iId = c.col("stop_id"), iName = c.col("stop_name"), iLat = c.col("stop_lat"), iLon = c.col("stop_lon");
                String[] r;
                while ((r = c.row()) != null) {
                    sId.add(c.get(r, iId));
                    sName.add(c.get(r, iName));
                    sPos.add(new double[]{parseDoubleSafe(c.get(r, iLat)), parseDoubleSafe(c.get(r, iLon))});
                }
            }
            int ns = sId.size();
            g.stopId = sId.toArray(new String[0]);
            g.stopName = sName.toArray(new String[0]);
            g.lat = new double[ns];
            g.lon = new double[ns];
            for (int i = 0; i < ns; i++) {
                g.lat[i] = sPos.get(i)[0];
                g.lon[i] = sPos.get(i)[1];
                g.stopIdx.put(g.stopId[i], i);
            }

            // trips.txt
            ArrayList<String> tId = new ArrayList<>(), tLine = new ArrayList<>(), tSvc = new ArrayList<>(), tHead = new ArrayList<>();
            try (Csv c = Csv.open(z, "trips.txt")) {
                int iRoute = c.col("route_id"), iSvc = c.col("service_id"), iTrip = c.col("trip_id"), iHead = c.col("trip_headsign");
                String[] r;
                while ((r = c.row()) != null) {
                    String line = use.get(c.get(r, iRoute));
                    if (line == null) continue;
                    g.tripIdx.put(c.get(r, iTrip), tId.size());
                    tId.add(c.get(r, iTrip));
                    tLine.add(line);
                    tSvc.add(c.get(r, iSvc));
                    tHead.add(c.get(r, iHead));
                }
            }
            int nt = tId.size();
            g.nTrips = nt;
            g.tripId = tId.toArray(new String[0]);
            g.tripLine = tLine.toArray(new String[0]);
            g.tripService = tSvc.toArray(new String[0]);
            g.tripHeadsign = tHead.toArray(new String[0]);

            // stop_times.txt
            IntList[] stops = new IntList[nt], seqs = new IntList[nt], arrs = new IntList[nt], deps = new IntList[nt];
            try (Csv c = Csv.open(z, "stop_times.txt")) {
                int iTrip = c.col("trip_id"), iArr = c.col("arrival_time"), iDep = c.col("departure_time"), iStop = c.col("stop_id"), iSeq = c.col("stop_sequence");
                int maxCol = Math.max(Math.max(Math.max(iTrip, iArr), Math.max(iDep, iStop)), iSeq);
                String[] f = new String[maxCol + 1];
                String line, lastTrip = null;
                int lastT = -1;
                long n = 0;
                while ((line = c.line()) != null) {
                    if ((++n & 16383) == 0 && prog != null) prog.set("parse", c.bytesRead(), c.size);
                    if (line.isEmpty()) continue;
                    if (line.indexOf('"') >= 0) {
                        String[] r = Csv.split(line);
                        for (int k = 0; k <= maxCol; k++) f[k] = k < r.length ? r[k] : "";
                    } else fastFields(line, f, maxCol);
                    String tid = iTrip < 0 ? "" : f[iTrip];
                    int t;
                    if (tid.equals(lastTrip)) t = lastT;
                    else {
                        Integer ti = g.tripIdx.get(tid);
                        t = ti == null ? -1 : ti;
                        lastTrip = tid;
                        lastT = t;
                    }
                    if (t < 0) continue;
                    Integer s = iStop < 0 ? null : g.stopIdx.get(f[iStop]);
                    if (s == null) continue;
                    int dep = iDep < 0 ? -1 : parseTime(f[iDep]);
                    int arr = iArr < 0 ? -1 : parseTime(f[iArr]);
                    if (dep < 0) dep = arr;
                    if (arr < 0) arr = dep;
                    if (dep < 0) continue;
                    if (stops[t] == null) { stops[t] = new IntList(); seqs[t] = new IntList(); arrs[t] = new IntList(); deps[t] = new IntList(); }
                    stops[t].add(s);
                    seqs[t].add(iSeq < 0 ? stops[t].size : parseIntSafe(f[iSeq], stops[t].size));
                    arrs[t].add(arr);
                    deps[t].add(dep);
                }
            }
            g.tStop = new int[nt][];
            g.tSeq = new int[nt][];
            g.tArr = new int[nt][];
            g.tDep = new int[nt][];
            for (int t = 0; t < nt; t++) {
                if (stops[t] == null) {
                    g.tStop[t] = g.tSeq[t] = g.tArr[t] = g.tDep[t] = new int[0];
                    continue;
                }
                int n = stops[t].size;
                Integer[] order = new Integer[n];
                for (int k = 0; k < n; k++) order[k] = k;
                final int[] sq = seqs[t].a;
                Arrays.sort(order, (a, b) -> Integer.compare(sq[a], sq[b]));
                g.tStop[t] = new int[n]; g.tSeq[t] = new int[n]; g.tArr[t] = new int[n]; g.tDep[t] = new int[n];
                for (int k = 0; k < n; k++) {
                    int o = order[k];
                    g.tStop[t][k] = stops[t].a[o];
                    g.tSeq[t][k] = seqs[t].a[o];
                    g.tArr[t][k] = arrs[t].a[o];
                    g.tDep[t][k] = deps[t].a[o];
                }
            }

            // calendar.txt (może nie istnieć)
            if (z.getEntry("calendar.txt") != null) {
                try (Csv c = Csv.open(z, "calendar.txt")) {
                    String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
                    int[] idx = new int[7];
                    for (int d = 0; d < 7; d++) idx[d] = c.col(days[d]);
                    int iSvc = c.col("service_id"), iStart = c.col("start_date"), iEnd = c.col("end_date");
                    String[] r;
                    while ((r = c.row()) != null) {
                        int mask = 0;
                        for (int d = 0; d < 7; d++) if ("1".equals(c.get(r, idx[d]))) mask |= 1 << d;
                        g.cal.put(c.get(r, iSvc), new int[]{mask, parseIntSafe(c.get(r, iStart), 0), parseIntSafe(c.get(r, iEnd), 99999999)});
                    }
                }
            }
            if (z.getEntry("calendar_dates.txt") != null) {
                try (Csv c = Csv.open(z, "calendar_dates.txt")) {
                    int iSvc = c.col("service_id"), iDate = c.col("date"), iEx = c.col("exception_type");
                    String[] r;
                    while ((r = c.row()) != null) {
                        g.calDates.put(c.get(r, iSvc) + "|" + c.get(r, iDate), parseIntSafe(c.get(r, iEx), 1));
                    }
                }
            }
        }
        g.buildIndexes();
        return g;
    }

    private void buildIndexes() {
        TreeSet<String> ls = new TreeSet<>(Gtfs::compareLines);
        linesAtName.clear();
        for (int t = 0; t < nTrips; t++) {
            if (tStop[t].length == 0) continue;
            ls.add(tripLine[t]);
            for (int s : tStop[t]) {
                String key = norm(stopName[s]);
                TreeSet<String> set = linesAtName.get(key);
                if (set == null) { set = new TreeSet<>(Gtfs::compareLines); linesAtName.put(key, set); }
                set.add(tripLine[t]);
            }
        }
        lines = ls.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ zapis / odczyt pamięci podręcznej

    void save(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            o.writeInt(CACHE_VERSION);
            o.writeInt(stopId.length);
            for (int i = 0; i < stopId.length; i++) {
                o.writeUTF(stopId[i]); o.writeUTF(stopName[i]); o.writeDouble(lat[i]); o.writeDouble(lon[i]);
            }
            o.writeInt(routeShort.size());
            for (Map.Entry<String, String> e : routeShort.entrySet()) { o.writeUTF(e.getKey()); o.writeUTF(e.getValue()); }
            o.writeInt(nTrips);
            for (int t = 0; t < nTrips; t++) {
                o.writeUTF(tripId[t]); o.writeUTF(tripLine[t]); o.writeUTF(tripService[t]); o.writeUTF(tripHeadsign[t]);
                int n = tStop[t].length;
                o.writeInt(n);
                for (int k = 0; k < n; k++) { o.writeInt(tStop[t][k]); o.writeInt(tSeq[t][k]); o.writeInt(tArr[t][k]); o.writeInt(tDep[t][k]); }
            }
            o.writeInt(cal.size());
            for (Map.Entry<String, int[]> e : cal.entrySet()) { o.writeUTF(e.getKey()); o.writeInt(e.getValue()[0]); o.writeInt(e.getValue()[1]); o.writeInt(e.getValue()[2]); }
            o.writeInt(calDates.size());
            for (Map.Entry<String, Integer> e : calDates.entrySet()) { o.writeUTF(e.getKey()); o.writeInt(e.getValue()); }
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new IOException("nie mozna zapisac pamieci podrecznej");
    }

    static Gtfs load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != CACHE_VERSION) throw new IOException("stara wersja");
            Gtfs g = new Gtfs();
            int ns = in.readInt();
            g.stopId = new String[ns]; g.stopName = new String[ns]; g.lat = new double[ns]; g.lon = new double[ns];
            for (int i = 0; i < ns; i++) {
                g.stopId[i] = in.readUTF(); g.stopName[i] = in.readUTF(); g.lat[i] = in.readDouble(); g.lon[i] = in.readDouble();
                g.stopIdx.put(g.stopId[i], i);
            }
            int nr = in.readInt();
            for (int i = 0; i < nr; i++) g.routeShort.put(in.readUTF(), in.readUTF());
            int nt = in.readInt();
            g.nTrips = nt;
            g.tripId = new String[nt]; g.tripLine = new String[nt]; g.tripService = new String[nt]; g.tripHeadsign = new String[nt];
            g.tStop = new int[nt][]; g.tSeq = new int[nt][]; g.tArr = new int[nt][]; g.tDep = new int[nt][];
            for (int t = 0; t < nt; t++) {
                g.tripId[t] = in.readUTF(); g.tripLine[t] = in.readUTF(); g.tripService[t] = in.readUTF(); g.tripHeadsign[t] = in.readUTF();
                g.tripIdx.put(g.tripId[t], t);
                int n = in.readInt();
                g.tStop[t] = new int[n]; g.tSeq[t] = new int[n]; g.tArr[t] = new int[n]; g.tDep[t] = new int[n];
                for (int k = 0; k < n; k++) { g.tStop[t][k] = in.readInt(); g.tSeq[t][k] = in.readInt(); g.tArr[t][k] = in.readInt(); g.tDep[t][k] = in.readInt(); }
            }
            int nc = in.readInt();
            for (int i = 0; i < nc; i++) { String k = in.readUTF(); g.cal.put(k, new int[]{in.readInt(), in.readInt(), in.readInt()}); }
            int nd = in.readInt();
            for (int i = 0; i < nd; i++) { String k = in.readUTF(); g.calDates.put(k, in.readInt()); }
            g.buildIndexes();
            return g;
        }
    }

    // ------------------------------------------------------------------ zapytania

    boolean serviceActive(String service, LocalDate d) {
        int ymd = d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
        Integer ex = calDates.get(service + "|" + ymd);
        if (ex != null) return ex == 1;
        int[] c = cal.get(service);
        if (c == null) return false;
        if (ymd < c[1] || ymd > c[2]) return false;
        int dow = d.getDayOfWeek().getValue() - 1; // pn = 0
        return (c[0] & (1 << dow)) != 0;
    }

    /** Wszystkie przystanki (słupki) o danej nazwie. */
    boolean[] stopsNamed(String name) {
        boolean[] m = new boolean[stopId.length];
        String n = norm(name);
        for (int i = 0; i < stopId.length; i++) if (norm(stopName[i]).equals(n)) m[i] = true;
        return m;
    }

    /** Nazwa przystanku w oryginalnej pisowni (albo null, gdy brak). */
    String canonicalName(String name) {
        String n = norm(name);
        for (String s : stopName) if (norm(s).equals(n)) return s;
        return null;
    }

    /** Wyszukiwarka przystanków tramwajowych: nazwy + linie, które się na nich zatrzymują. */
    List<String[]> searchStops(String q, int limit) {
        String nq = norm(q);
        TreeSet<String> seen = new TreeSet<>();
        ArrayList<String[]> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (String s : stopName) {
            String n = norm(s);
            if (seen.contains(n)) continue;
            TreeSet<String> l = linesAtName.get(n);
            if (l == null) continue; // brak tramwajów
            if (!nq.isEmpty() && !n.contains(nq)) continue;
            seen.add(n);
            String[] item = {s, String.join(", ", l)};
            if (n.startsWith(nq)) starts.add(item); else contains.add(item);
        }
        Collections.sort(starts, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        Collections.sort(contains, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        starts.addAll(contains);
        return starts.size() > limit ? starts.subList(0, limit) : starts;
    }

    // ------------------------------------------------------------------ narzędzia

    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replace('ł', 'l');
        return t.replaceAll("[\\s\\-–.]+", " ").trim();
    }

    static int compareLines(String a, String b) {
        int na = leadingInt(a), nb = leadingInt(b);
        if (na != nb) return Integer.compare(na, nb);
        return a.compareTo(b);
    }

    private static int leadingInt(String s) {
        int v = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) { v = v * 10 + (s.charAt(i) - '0'); i++; }
        return i == 0 ? Integer.MAX_VALUE : v;
    }

    static int parseTime(String s) {
        if (s == null) return -1;
        int v = 0, part = 0, total = 0, len = s.length();
        boolean any = false;
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') { v = v * 10 + (ch - '0'); any = true; }
            else if (ch == ':') {
                total = total * 60 + v; v = 0; part++;
            } else if (ch != ' ') return -1;
        }
        if (!any || part < 1) return -1;
        total = total * 60 + v;
        if (part == 1) total *= 60; // HH:MM
        return total;
    }

    /** Dzieli wiersz bez cudzysłowów na pola 0..maxCol (reszta ignorowana). */
    static void fastFields(String line, String[] out, int maxCol) {
        int start = 0;
        for (int k = 0; k <= maxCol; k++) {
            if (start > line.length()) { out[k] = ""; continue; }
            int e = line.indexOf(',', start);
            if (e < 0) e = line.length();
            out[k] = line.substring(start, e);
            start = e + 1;
        }
    }

    static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    static final class IntList {
        int[] a = new int[32];
        int size;
        void add(int v) {
            if (size == a.length) a = Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }
    }

    /** Prosty czytnik CSV (obsługuje cudzysłowy). */
    static final class Csv implements AutoCloseable {
        private final BufferedReader r;
        private final HashMap<String, Integer> head = new HashMap<>();
        private final long[] counter = new long[1];
        long size = -1;

        long bytesRead() { return counter[0]; }

        String line() throws IOException { return r.readLine(); }

        private Csv(InputStream raw) throws IOException {
            InputStream in = new java.io.FilterInputStream(raw) {
                @Override public int read() throws IOException { int b = super.read(); if (b >= 0) counter[0]++; return b; }
                @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) counter[0] += n; return n; }
            };
            r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
            String h = r.readLine();
            if (h == null) return;
            if (h.startsWith("﻿")) h = h.substring(1);
            String[] cols = split(h);
            for (int i = 0; i < cols.length; i++) head.put(cols[i].trim(), i);
        }

        static Csv open(ZipFile z, String name) throws IOException {
            ZipEntry e = z.getEntry(name);
            if (e == null) {
                // czasem pliki leżą w podkatalogu
                java.util.Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry x = en.nextElement();
                    if (x.getName().endsWith("/" + name)) { e = x; break; }
                }
            }
            if (e == null) throw new IOException("brak pliku " + name + " w GTFS");
            Csv c = new Csv(z.getInputStream(e));
            c.size = e.getSize();
            return c;
        }

        int col(String name) {
            Integer i = head.get(name);
            return i == null ? -1 : i;
        }

        String get(String[] row, int i) {
            return i < 0 || i >= row.length ? "" : row[i];
        }

        String[] row() throws IOException {
            String line;
            do {
                line = r.readLine();
                if (line == null) return null;
            } while (line.isEmpty());
            if (line.indexOf('"') < 0) return line.split(",", -1);
            // pole w cudzysłowie może zawierać nową linię
            while (countQuotes(line) % 2 == 1) {
                String more = r.readLine();
                if (more == null) break;
                line = line + "\n" + more;
            }
            return split(line);
        }

        private static int countQuotes(String s) {
            int n = 0;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '"') n++;
            return n;
        }

        static String[] split(String line) {
            ArrayList<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (q) {
                    if (ch == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                        else q = false;
                    } else cur.append(ch);
                } else if (ch == '"') q = true;
                else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(ch);
            }
            out.add(cur.toString());
            return out.toArray(new String[0]);
        }

        @Override
        public void close() throws IOException { r.close(); }
    }
}
