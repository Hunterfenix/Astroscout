# Tile del cielo (World Atlas 2015)

L'app scarica su richiesta tile 5°×5° di SQM generate da questo script e le tiene offline.
Va fatto **una volta sola** (o quando esce un atlante nuovo).

## 1. Scarica il World Atlas (~2.9 GB)

Pagina del dataset: <https://doi.org/10.5880/GFZ.1.4.2016.001> (GFZ Data Services).
Scarica lo zip, estrai il GeoTIFF (es. `World_Atlas_2015.tif`).
Licenza **CC BY-NC 4.0**: uso non commerciale e attribuzione (già presente nella schermata Informazioni).

## 2. Genera le tile

```bash
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install rasterio numpy

# tutto il mondo
python tools/build_sky_tiles.py /percorso/World_Atlas_2015.tif --out sky-tiles
# oppure solo Europa (più veloce, meno file)
python tools/build_sky_tiles.py /percorso/World_Atlas_2015.tif --out sky-tiles --bbox -25 34 45 72
```

Lo script stampa quante tile ha scritto. Se sono più di 1000 (limite asset di una GitHub Release)
usa `--bbox` oppure `--tile-deg 10`.

## 3. Pubblica le tile come GitHub Release

Il repo è pubblico, quindi gli asset di una release sono scaricabili senza autenticazione.
Il tag **deve** essere `sky-atlas-wa2015` (oppure cambia `skyatlas.baseUrl` in `local.properties`).

**Da terminale** (con [GitHub CLI](https://cli.github.com/)):
```bash
gh release create sky-atlas-wa2015 sky-tiles/* \
  --repo Hunterfenix/Astroscout \
  --title "Sky atlas tiles (World Atlas 2015)" \
  --notes "Falchi et al. 2016, doi:10.5880/GFZ.1.4.2016.001 — CC BY-NC 4.0"
```

**Dal sito**: repo → *Releases* → *Draft a new release* → tag `sky-atlas-wa2015` →
trascina tutti i file di `sky-tiles/` (incluso `manifest.json`) → *Publish release*.

## 4. Verifica

Apri nel browser
`https://github.com/Hunterfenix/Astroscout/releases/download/sky-atlas-wa2015/manifest.json`:
deve scaricare il JSON. Poi nell'app tocca un punto: nella scheda la fonte deve essere
"World Atlas 2015".

## Aggiornare

Rigenera con `--version wa2015-2` (o un nuovo dataset) e sostituisci gli asset della release.
Le app vedono la nuova versione al controllo mensile del manifest, cancellano le tile vecchie e
riscaricano solo quelle che servono.
