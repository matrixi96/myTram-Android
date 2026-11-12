package pl.mytram.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Dane na żywo MPK Łódź (GTFS-Realtime): opóźnienia, pozycje tramwajów, komunikaty. */
final class Rt {

    static final class Stu {
        int seq = -1;
        String stopId = "";
        boolean hasDelay;
        int delay;
        long time;
    }

    static final class TripRt {
        final List<Stu> stus = new ArrayList<>();
        boolean hasDelay;
        int delay;
        boolean canceled;
    }

    static final class Veh {
        double lat, lon;
        int seq = -1;
        String stopId = "";
        int status = 2; // 0 dojeżdża, 1 stoi, 2 w drodze
        boolean hasPos;
        String label = "";
    }

    static final class Alert {
        String header = "", text = "";
        final List<String> routes = new ArrayList<>();
        final List<String> stops = new ArrayList<>();
        final List<long[]> periods = new ArrayList<>();

        boolean activeAt(long nowSec) {
            if (periods.isEmpty()) return true;
            for (long[] p : periods) {
                long s = p[0], e = p[1];
                if (s > 100000000000L) s /= 1000; // ktoś podał milisekundy
                if (e > 100000000000L) e /= 1000;
                if ((s == 0 || s <= nowSec) && (e == 0 || nowSec <= e)) return true;
            }
            return false;
        }
    }

    final HashMap<String, TripRt> trips = new HashMap<>();
    final HashMap<String, Veh> vehicles = new HashMap<>();
    final List<Alert> alerts = new ArrayList<>();

    // ------------------------------------------------------------------ TripUpdates

    void parseTripUpdates(byte[] data) {
        Pb feed = new Pb(data);
        while (feed.next()) {
            if (feed.field != 2 || feed.wire != 2) { feed.skip(); continue; }
            Pb ent = feed.msg();
            while (ent.next()) {
                if (ent.field == 3 && ent.wire == 2) parseTripUpdate(ent.msg());
                else ent.skip();
            }
        }
    }

    private void parseTripUpdate(Pb tu) {
        String tripId = null;
        TripRt tr = new TripRt();
        while (tu.next()) {
            if (tu.field == 1 && tu.wire == 2) {
                Pb td = tu.msg();
                while (td.next()) {
                    if (td.field == 1 && td.wire == 2) tripId = td.str();
                    else if (td.field == 4 && td.wire == 0) tr.canceled = td.varint() == 3;
                    else td.skip();
                }
            } else if (tu.field == 2 && tu.wire == 2) {
                Pb su = tu.msg();
                Stu s = new Stu();
                Stu arr = null, dep = null;
                while (su.next()) {
                    if (su.field == 1 && su.wire == 0) s.seq = (int) su.varint();
                    else if (su.field == 4 && su.wire == 2) s.stopId = su.str();
                    else if ((su.field == 2 || su.field == 3) && su.wire == 2) {
                        Stu ev = new Stu();
                        Pb e = su.msg();
                        while (e.next()) {
                            if (e.field == 1 && e.wire == 0) { ev.hasDelay = true; ev.delay = (int) e.varint(); }
                            else if (e.field == 2 && e.wire == 0) ev.time = e.varint();
                            else e.skip();
                        }
                        if (su.field == 2) arr = ev; else dep = ev;
                    } else su.skip();
                }
                Stu ev = dep != null && (dep.hasDelay || dep.time > 0) ? dep : arr;
                if (ev != null) { s.hasDelay = ev.hasDelay; s.delay = ev.delay; s.time = ev.time; }
                tr.stus.add(s);
            } else if (tu.field == 5 && tu.wire == 0) {
                tr.hasDelay = true;
                tr.delay = (int) tu.varint();
            } else tu.skip();
        }
        if (tripId != null) trips.put(tripId, tr);
    }

    // ------------------------------------------------------------------ VehiclePositions

    void parseVehicles(byte[] data) {
        Pb feed = new Pb(data);
        while (feed.next()) {
            if (feed.field != 2 || feed.wire != 2) { feed.skip(); continue; }
            Pb ent = feed.msg();
            while (ent.next()) {
                if (ent.field == 4 && ent.wire == 2) parseVehicle(ent.msg());
                else ent.skip();
            }
        }
    }

    private void parseVehicle(Pb vp) {
        String tripId = null;
        Veh v = new Veh();
        while (vp.next()) {
            if (vp.field == 1 && vp.wire == 2) {
                Pb td = vp.msg();
                while (td.next()) {
                    if (td.field == 1 && td.wire == 2) tripId = td.str();
                    else td.skip();
                }
            } else if (vp.field == 2 && vp.wire == 2) {
                Pb p = vp.msg();
                while (p.next()) {
                    if (p.field == 1 && p.wire == 5) { v.lat = p.f32(); v.hasPos = true; }
                    else if (p.field == 2 && p.wire == 5) v.lon = p.f32();
                    else if (p.field == 1 && p.wire == 1) { v.lat = p.f64(); v.hasPos = true; }
                    else if (p.field == 2 && p.wire == 1) v.lon = p.f64();
                    else p.skip();
                }
            } else if (vp.field == 3 && vp.wire == 0) v.seq = (int) vp.varint();
            else if (vp.field == 4 && vp.wire == 0) v.status = (int) vp.varint();
            else if (vp.field == 7 && vp.wire == 2) v.stopId = vp.str();
            else if (vp.field == 8 && vp.wire == 2) {
                Pb vd = vp.msg();
                while (vd.next()) {
                    if (vd.field == 2 && vd.wire == 2) v.label = vd.str();
                    else if (vd.field == 1 && vd.wire == 2 && v.label.isEmpty()) v.label = vd.str();
                    else vd.skip();
                }
            } else vp.skip();
        }
        if (tripId != null) vehicles.put(tripId, v);
    }

    // ------------------------------------------------------------------ Alerts

    void parseAlerts(byte[] data) {
        Pb feed = new Pb(data);
        while (feed.next()) {
            if (feed.field != 2 || feed.wire != 2) { feed.skip(); continue; }
            Pb ent = feed.msg();
            while (ent.next()) {
                if (ent.field == 5 && ent.wire == 2) {
                    Alert a = parseAlert(ent.msg());
                    if (!a.header.isEmpty() || !a.text.isEmpty()) alerts.add(a);
                } else ent.skip();
            }
        }
    }

    private Alert parseAlert(Pb al) {
        Alert a = new Alert();
        while (al.next()) {
            if (al.field == 1 && al.wire == 2) {
                Pb tr = al.msg();
                long s = 0, e = 0;
                while (tr.next()) {
                    if (tr.field == 1 && tr.wire == 0) s = tr.varint();
                    else if (tr.field == 2 && tr.wire == 0) e = tr.varint();
                    else tr.skip();
                }
                a.periods.add(new long[]{s, e});
            } else if (al.field == 5 && al.wire == 2) {
                Pb es = al.msg();
                while (es.next()) {
                    if (es.field == 2 && es.wire == 2) a.routes.add(es.str());
                    else if (es.field == 5 && es.wire == 2) a.stops.add(es.str());
                    else if (es.field == 4 && es.wire == 2) {
                        Pb td = es.msg();
                        while (td.next()) {
                            if (td.field == 5 && td.wire == 2) a.routes.add(td.str());
                            else td.skip();
                        }
                    } else es.skip();
                }
            } else if (al.field == 10 && al.wire == 2) a.header = translated(al.msg());
            else if (al.field == 11 && al.wire == 2) a.text = translated(al.msg());
            else al.skip();
        }
        return a;
    }

    private static String translated(Pb ts) {
        String first = null, pl = null;
        while (ts.next()) {
            if (ts.field == 1 && ts.wire == 2) {
                Pb t = ts.msg();
                String text = "", lang = "";
                while (t.next()) {
                    if (t.field == 1 && t.wire == 2) text = t.str();
                    else if (t.field == 2 && t.wire == 2) lang = t.str();
                    else t.skip();
                }
                if (first == null) first = text;
                if (pl == null && (lang.isEmpty() || lang.toLowerCase().startsWith("pl"))) pl = text;
            } else ts.skip();
        }
        String r = pl != null ? pl : first;
        return r == null ? "" : r.trim();
    }
}
