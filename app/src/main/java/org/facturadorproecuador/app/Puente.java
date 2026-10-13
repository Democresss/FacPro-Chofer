package org.facturadorproecuador.app;

import android.webkit.JavascriptInterface;

/** Lo que la página puede pedirle al teléfono: window.FacProNativo (solo existe dentro de la app). */
public class Puente {

    private final MainActivity app;

    Puente(MainActivity app) {
        this.app = app;
    }

    @JavascriptInterface
    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    @JavascriptInterface
    public String app() {
        return BuildConfig.CHOFER ? "chofer" : "pedidos";
    }

    /** En ruta (activo=true) el servicio sigue mandando la ubicación y los puntos aunque la pantalla se apague. */
    @JavascriptInterface
    public void rastrear(String chofer, String ruta, boolean activo) {
        final String c = chofer == null ? "" : chofer;
        final String r = ruta == null ? "" : ruta;
        app.runOnUiThread(() -> app.rastrear(c, r, activo));
    }

    /** Puntos que tomó el teléfono mientras la app estaba oculta (para el recorrido de la página). */
    @JavascriptInterface
    public String tomarPuntos(String ruta) {
        return Puntos.tomarParaWeb(app, ruta == null ? "" : ruta);
    }

    @JavascriptInterface
    public boolean gpsListo() {
        return MainActivity.tienePermisoUbicacion(app);
    }

    @JavascriptInterface
    public void abrirAjustesUbicacion(boolean permiso) {
        app.runOnUiThread(() -> app.abrirAjustesUbicacion(permiso));
    }

    // ── Correo y contraseña guardados en el teléfono (cifrados): solo para las páginas de FacturaPro ──
    private boolean paginaNuestra() {
        String u = app.urlActual;
        return u != null && MainActivity.esNuestro(android.net.Uri.parse(u));
    }

    @JavascriptInterface
    public String acceso() {
        return paginaNuestra() ? Boveda.leer(app) : "";
    }

    @JavascriptInterface
    public void guardarAcceso(String usuario, String clave) {
        if (paginaNuestra()) Boveda.guardar(app, usuario, clave);
    }

    @JavascriptInterface
    public void borrarAcceso() {
        Boveda.borrar(app);
    }

    /** Cerró sesión a mano: la próxima vez no entra sola. */
    @JavascriptInterface
    public void marcarSalida() {
        Boveda.marcarSalida(app);
    }

    @JavascriptInterface
    public boolean salio() {
        return Boveda.salio(app);
    }

    @JavascriptInterface
    public void guardarArchivo(String dataUrl, String nombre) {
        app.guardarArchivo(dataUrl, nombre);
    }
}
