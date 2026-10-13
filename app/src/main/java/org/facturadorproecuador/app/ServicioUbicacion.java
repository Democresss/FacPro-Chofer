package org.facturadorproecuador.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GPS de la ruta con la pantalla apagada o con el chofer en otra app (WhatsApp, Maps…): servicio en primer plano con
 * un aviso fijo. Mientras la app está a la vista, la ubicación la manda la página (como siempre); con la app oculta,
 * este servicio toma los puntos y los manda al mismo lugar que la web (/v2/chofer/api/update-location).
 */
public class ServicioUbicacion extends Service implements LocationListener {

    static final String DETENER = "detener";
    private static final String CANAL = "rastreo";
    private static final int AVISO = 7;
    private static final long CADA_ENVIO = 30_000;
    private static final long SIN_WEB_MAX = 14L * 3600 * 1000;   // sin noticias de la página en 14 h: se apaga

    static volatile boolean activo = false;
    static volatile String chofer = "";
    static volatile String ruta = "";
    static volatile long contactoWeb = 0;

    private HandlerThread hilo;
    private Handler h;
    private LocationManager lm;
    private PowerManager.WakeLock despierto;
    private volatile Location ultima;
    private long ultimaGps = 0;
    private boolean gpsApagado = false;

    private final Runnable tic = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - contactoWeb > SIN_WEB_MAX) {
                detenerTodo();
                return;
            }
            enviar();
            if (activo) h.postDelayed(this, CADA_ENVIO);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        hilo = new HandlerThread("facpro-gps");
        hilo.start();
        h = new Handler(hilo.getLooper());
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Ubicación en ruta", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Aviso fijo mientras se comparte tu ubicación con la oficina");
            canal.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(canal);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (intent != null && DETENER.equals(intent.getAction())) {
            h.post(() -> {
                enviar();
                detenerTodo();
            });
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra("chofer")) {
            chofer = intent.getStringExtra("chofer");
            ruta = intent.getStringExtra("ruta") == null ? "" : intent.getStringExtra("ruta");
            sp.edit().putString("rastreo_chofer", chofer).putString("rastreo_ruta", ruta).apply();
        } else {
            // Android lo reinició después de cerrarlo: sigue con la misma ruta
            chofer = sp.getString("rastreo_chofer", "");
            ruta = sp.getString("rastreo_ruta", "");
        }
        contactoWeb = System.currentTimeMillis();
        if (chofer == null || chofer.isEmpty() || !MainActivity.tienePermisoUbicacion(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(AVISO, aviso(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(AVISO, aviso());
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!activo) {
            activo = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                despierto = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "facpro:gps");
                despierto.setReferenceCounted(false);
                despierto.acquire(SIN_WEB_MAX);
            }
            pedirUbicaciones();
            h.postDelayed(tic, CADA_ENVIO);
        }
        return START_STICKY;
    }

    private Notification aviso() {
        Intent abrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String texto = gpsApagado
                ? "La ubicación del teléfono está apagada: enciéndela para seguir con la ruta"
                : "Compartiendo tu ubicación con la oficina mientras estás en ruta";
        return new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .build();
    }

    private void actualizarAviso() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && activo) {
            try {
                nm.notify(AVISO, aviso());
            } catch (Exception ignorado) {
            }
        }
    }

    private void pedirUbicaciones() {
        try {
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this, hilo.getLooper());
                gpsApagado = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            }
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 25f, this, hilo.getLooper());
            }
            actualizarAviso();
        } catch (SecurityException e) {
            detenerTodo();
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        long ahora = System.currentTimeMillis();
        if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            ultimaGps = ahora;
        } else if (ahora - ultimaGps < 30_000) {
            return;          // con GPS funcionando, la ubicación por antenas es menos precisa
        }
        if (l.hasAccuracy() && l.getAccuracy() > 150) return;
        ultima = l;
        // Con la app a la vista los puntos los toma la página (así no se duplican)
        if (!MainActivity.visible) Puntos.agregar(this, ruta, l.getLatitude(), l.getLongitude(), ahora);
    }

    @Override
    public void onProviderDisabled(String proveedor) {
        if (LocationManager.GPS_PROVIDER.equals(proveedor)) {
            gpsApagado = true;
            actualizarAviso();
        }
    }

    @Override
    public void onProviderEnabled(String proveedor) {
        if (LocationManager.GPS_PROVIDER.equals(proveedor)) {
            gpsApagado = false;
            actualizarAviso();
        }
    }

    @Override
    public void onStatusChanged(String proveedor, int estado, Bundle extras) {
    }

    /** Ubicación en vivo (si la app está oculta) y los puntos pendientes de la ruta. */
    private void enviar() {
        Location l = ultima;
        Object[] lote = Puntos.lote(this);
        boolean enVivo = !MainActivity.visible && l != null;
        if (!enVivo && lote == null) return;
        try {
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("driver_id", chofer);
            if (l != null) {
                cuerpo.put("lat", l.getLatitude());
                cuerpo.put("lng", l.getLongitude());
                cuerpo.put("speed", l.hasSpeed() ? l.getSpeed() : 0);
                cuerpo.put("accuracy", l.hasAccuracy() ? l.getAccuracy() : 0);
            } else {
                JSONArray pts = (JSONArray) lote[1];
                JSONArray u = pts.optJSONArray(pts.length() - 1);
                if (u == null) return;
                cuerpo.put("lat", u.optDouble(0));
                cuerpo.put("lng", u.optDouble(1));
            }
            cuerpo.put("nativo", true);
            if (lote != null && !((String) lote[0]).isEmpty()) {
                cuerpo.put("route_id", lote[0]);
                cuerpo.put("puntos", lote[1]);
            }
            String respuesta = post(BuildConfig.SITIO + "/v2/chofer/api/update-location", cuerpo.toString());
            // Solo se borran del teléfono cuando el servidor los guardó de verdad
            boolean guardados = respuesta != null && !respuesta.contains("recorrido_error");
            if (guardados && lote != null) Puntos.confirmar(this, (Integer) lote[2]);
            if (guardados && lote != null && (Integer) lote[2] >= 500) h.post(this::enviar);   // quedaban más
        } catch (Exception ignorado) {
            // sin señal: se reintenta en el próximo envío
        }
    }

    /** Respuesta del servidor si contestó 200; null si no. */
    private String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "FacProApp/" + BuildConfig.VERSION_NAME + " (chofer; servicio)");
            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie(BuildConfig.SITIO);
            } catch (Exception ignorado) {
            }
            if (cookie == null || cookie.isEmpty()) cookie = Puntos.cookie(this);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            try (OutputStream o = c.getOutputStream()) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int codigo = c.getResponseCode();
            java.io.ByteArrayOutputStream leido = new java.io.ByteArrayOutputStream();
            try (InputStream i = codigo < 400 ? c.getInputStream() : c.getErrorStream()) {
                if (i != null) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = i.read(b)) != -1) if (leido.size() < 65536) leido.write(b, 0, n);
                }
            }
            return codigo == 200 ? leido.toString("UTF-8") : null;
        } finally {
            c.disconnect();
        }
    }

    private void detenerTodo() {
        activo = false;
        try {
            lm.removeUpdates(this);
        } catch (Exception ignorado) {
        }
        h.removeCallbacks(tic);
        if (despierto != null && despierto.isHeld()) despierto.release();
        getSharedPreferences("app", MODE_PRIVATE).edit().remove("rastreo_chofer").remove("rastreo_ruta").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        activo = false;
        try {
            lm.removeUpdates(this);
        } catch (Exception ignorado) {
        }
        if (despierto != null && despierto.isHeld()) despierto.release();
        hilo.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
