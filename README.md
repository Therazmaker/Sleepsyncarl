# SleepSync

Lee el sueño desde Health Connect (Mi Fitness) y lo envía por POST a tu backend.

## Compilar el APK
1. Sube esta carpeta a un repo de GitHub (rama `main`).
2. Pestaña **Actions** → "Build APK" (se ejecuta solo al hacer push, o con "Run workflow").
3. Al terminar, abre la ejecución y descarga el artefacto **sleepsync-apk**.
4. Descomprime e instala `app-debug.apk` en el teléfono (permite "instalar apps desconocidas").

## Usar
1. Abre la app → **Dar permiso de sueño**.
2. Deja la URL vacía para probar: muestra el JSON en pantalla.
3. Pon la URL de tu backend (y token si lo necesitas) → **Sincronizar**.

## JSON que envía
{ "source": "health-connect", "synced_at": "...", "sessions": [
  { "id", "origin", "start", "end", "start_offset", "duration_minutes",
    "title", "notes", "stage_totals_minutes": {"light":..,"deep":..,"rem":..,"awake":..},
    "stages": [ {"stage","start","end","minutes"} ] } ] }
