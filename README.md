# AstroScout

App Android nativa (Kotlin, Jetpack Compose) per trovare il miglior punto di ripresa astrofotografica
incrociando **inquinamento luminoso**, **meteo astronomico** (7Timer! ASTRO) e **parcheggi vicini**
(OpenStreetMap / Overpass).

## Setup

1. Android Studio Ladybug o successivo, JDK 17.
2. `cp local.properties.example local.properties` e compila i valori (vedi i commenti nel file).
   - Senza `darksky.apiKey` l'app usa una **sorgente di qualità del cielo simulata** e lo segnala nella UI.
3. `./gradlew :app:assembleDebug` · test: `./gradlew :app:testDebugUnitTest`

## Architettura

```
ui (Compose)  ──observe StateFlow──▶  MapViewModel  ──▶  AstroRepository  ──▶ Room (single source of truth)
     ▲                                     │                    │
     └── eventi one-shot (Channel) ◀───────┘                    ├─▶ SkyQualitySource (DarkSkySites | Simulated)
                                                                ├─▶ SevenTimerApi (Retrofit)
WorkManager (RefreshWorker, 6 h) ─────────────────────────────▶ ├─▶ OverpassApi (mirror fallback)
                                                                └─▶ NominatimApi (throttle 1 req/s)
```

| Package | Contenuto |
|---|---|
| `domain/` | Kotlin puro, nessuna dipendenza Android: modelli, `AstroScoreCalculator`, `NightForecastInterpreter`, `Bortle`, `Geo`. Testato su JVM. |
| `data/remote/` | DTO (`LightPollutionResponse`, `AstroForecastResponse`, `OverpassResponse`, `NominatimPlace`), API Retrofit, `NetworkModule`, mapper. |
| `data/sky/` | `SkyQualitySource` + `DarkSkySitesSource` + `SimulatedSkyQualitySource`. |
| `data/local/` | Room: `spots`, `forecasts` (per cella 0.1°), `parkings`. |
| `data/repository/` | `AstroRepository` (cache + TTL + fallback), mapping errori → `DataError`. |
| `ui/` | `MapScreen`, `SpotMap` (MapLibre in `AndroidView`), `SpotDetailSheet`, `SettingsScreen`, `AboutScreen`. |

### Flusso dati (stale-while-revalidate)

- La UI osserva **solo Room**. `refresh()` scarica ciò che ha superato la TTL e scrive in Room, che riemette.
- Se la rete fallisce resta l'ultimo valore valido, che la UI marca come "da cache".
- TTL: cielo 30 giorni · forecast 3 h (badge "cache" oltre 6 h) · parcheggi 7 giorni.
- Il forecast è cache-ato per **cella di 0.1°**, non per punto. Una griglia 5×5 a 5 km
  (20 km di lato) fa circa 4–9 chiamate 7Timer! invece di 25; un `Mutex` per cella evita i download doppi.
- Concorrenza di rete limitata a 4 (`Semaphore`). `CancellationException` non viene mai inghiottita.

### Astro Score

```
score = 100 · Σ wᵢ·sᵢ / Σ wᵢ          (solo componenti disponibili → isPartial)
s_LP     = clamp((SQM − 17.5) / 4.5)       oppure (9 − Bortle) / 8
s_cloud  = 1 − copertura media notturna    (0 se precipitazioni)
s_seeing = (8 − indice 7Timer!) / 7        idem per la trasparenza
cap: copertura ≥ 80 % o pioggia → score ≤ 20   (disattivabile)
```
Pesi di default 50/30/20/0, modificabili in Impostazioni (normalizzati automaticamente).
Finestra notturna: 20:00–04:00 ora locale; dopo mezzanotte si usa la notte in corso.

### Mappa

Una `GeoJsonSource` con data-driven styling. I layer, dal basso verso l'alto:
- alone verde (solo Bortle ≤ 4);
- cerchio colorato per Bortle (verde ≤ 4, giallo 5–6, rosso ≥ 7, bordo dorato per i preferiti);
- anello di selezione;
- badge bitmap meteo + score.

I badge sono bitmap perché lo style raster non ha `glyphs`.

## Note e limiti noti

- **DarkSkySites**: lo schema dell'API non è documentato pubblicamente. `LightPollutionResponse.fromJson`
  è tollerante (chiavi alternative, stringhe numeriche, annidamento sotto `data`/`result`); base URL e path
  sono in `local.properties`. Appena hai una risposta reale, aggiungi un test in `DtoParsingTest`.
- **7Timer!**: si prova HTTPS; solo se il TLS fallisce `HttpsFallbackInterceptor` riprova in HTTP.
  Il cleartext è consentito solo per `7timer.info` (`network_security_config.xml`).
- **Tile OSM**: `tile.openstreetmap.org` non è pensato per traffico da app. In produzione usa un provider
  dedicato (`map.tileUrl`).
- **Policy OSM**: User-Agent identificativo (`http.userAgent`, aggiungi un contatto), Nominatim al massimo
  1 req/s e ricerca solo all'invio, attribuzioni nella schermata Informazioni e nel pulsante (i) della mappa.

## Crediti

© OpenStreetMap contributors (ODbL) · Overpass API · Nominatim · 7Timer! (NOAA/NCEP GFS) · DarkSkySites · MapLibre Native (BSD-2).
