package org.facturadorproecuador.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Puntos GPS que toma el teléfono con la app oculta. Se guardan en el teléfono (sobreviven si Android cierra la app):
 * - «subir»: hasta que el servidor los recibe (sin señal se reintentan).
 * - «web»: para que la página los sume a su recorrido al volver (así cada entrega lleva su tramo completo).
 * Cada punto: [lat, lng, hora ISO UTC, milisegundos, ruta]. Mismo criterio que la web: uno cada 20 m o cada 2 min.
 */
final class Puntos {

    private static final int MAX_SUBIR = 6000;
    private static final int MAX_WEB = 4000;

    private Puntos() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("recorrido", Context.MODE_PRIVATE);
    }

    private static JSONArray lista(SharedPreferences sp, String clave) {
        try {
            return new JSONArray(sp.getString(clave, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** Devuelve true si el punto se guardó (lejos del anterior o pasó el tiempo). */
    static synchronized boolean agregar(Context c, String ruta, double lat, double lng, long ms) {
        if (ruta == null || ruta.isEmpty()) return false;
        SharedPreferences sp = sp(c);
        double uLat = Double.longBitsToDouble(sp.getLong("u_lat", 0));
        double uLng = Double.longBitsToDouble(sp.getLong("u_lng", 0));
        long uMs = sp.getLong("u_ms", 0);
        String uRuta = sp.getString("u_ruta", "");
        if (ruta.equals(uRuta) && uMs > 0) {
            double dLat = (lat - uLat) * 111320;
            double dLng = (lng - uLng) * 111320 * Math.cos(Math.toRadians(lat));
            double metros = Math.sqrt(dLat * dLat + dLng * dLng);
            if (metros < 20 && ms - uMs < 120000) return false;
        }
        JSONArray p = new JSONArray();
        try {
            p.put(Math.round(lat * 1e6) / 1e6).put(Math.round(lng * 1e6) / 1e6).put(iso(ms)).put(ms).put(ruta);
        } catch (JSONException e) {
            return false;
        }
        JSONArray subir = lista(sp, "subir");
        subir.put(p);
        while (subir.length() > MAX_SUBIR) subir.remove(0);
        JSONArray web = ruta.equals(sp.getString("web_ruta", "")) ? lista(sp, "web") : new JSONArray();
        web.put(p);
        while (web.length() > MAX_WEB) web.remove(0);
        sp.edit()
                .putString("subir", subir.toString())
                .putString("web", web.toString())
                .putString("web_ruta", ruta)
                .putLong("u_lat", Double.doubleToRawLongBits(lat))
                .putLong("u_lng", Double.doubleToRawLongBits(lng))
                .putLong("u_ms", ms)
                .putString("u_ruta", ruta)
                .apply();
        return true;
    }

    static synchronized int pendientes(Context c) {
        return lista(sp(c), "subir").length();
    }

    /** Siguiente lote para el servidor: puntos seguidos de una misma ruta (máx. 500). [ruta, [[lat,lng,iso]…], n] */
    static synchronized Object[] lote(Context c) {
        JSONArray subir = lista(sp(c), "subir");
        if (subir.length() == 0) return null;
        String ruta = subir.optJSONArray(0) == null ? "" : subir.optJSONArray(0).optString(4);
        JSONArray puntos = new JSONArray();
        int n = 0;
        for (int i = 0; i < subir.length() && n < 500; i++) {
            JSONArray p = subir.optJSONArray(i);
            if (p == null || !ruta.equals(p.optString(4))) break;
            try {
                puntos.put(new JSONArray().put(p.optDouble(0)).put(p.optDouble(1)).put(p.optString(2)));
            } catch (JSONException ignorado) {
            }
            n++;
        }
        return new Object[]{ruta, puntos, n};
    }

    static synchronized void confirmar(Context c, int n) {
        SharedPreferences sp = sp(c);
        JSONArray subir = lista(sp, "subir");
        JSONArray quedan = new JSONArray();
        for (int i = n; i < subir.length(); i++) quedan.put(subir.opt(i));
        sp.edit().putString("subir", quedan.toString()).apply();
    }

    /** Los puntos de esa ruta para la página ([lat,lng,iso,ms]); se entregan una sola vez. */
    static synchronized String tomarParaWeb(Context c, String ruta) {
        SharedPreferences sp = sp(c);
        JSONArray web = lista(sp, "web");
        JSONArray salida = new JSONArray();
        if (ruta.equals(sp.getString("web_ruta", ""))) {
            for (int i = 0; i < web.length(); i++) {
                JSONArray p = web.optJSONArray(i);
                if (p == null) continue;
                try {
                    salida.put(new JSONArray().put(p.optDouble(0)).put(p.optDouble(1)).put(p.optString(2)).put(p.optLong(3)));
                } catch (JSONException ignorado) {
                }
            }
        }
        sp.edit().putString("web", "[]").apply();
        return salida.toString();
    }

    // La sesión del chofer (para que el servicio mande la ubicación aunque la página no esté abierta)
    static void guardarCookie(Context c, String cookie) {
        if (cookie != null && !cookie.isEmpty()) sp(c).edit().putString("cookie", cookie).apply();
    }

    static String cookie(Context c) {
        return sp(c).getString("cookie", "");
    }
}
