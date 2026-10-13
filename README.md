# Book Widget

Un personaje pixel art pequeño y divertido, inspirado en el libro que lees, con una leyenda misteriosa
(≤ 50 palabras) que **pre-spoilea el siguiente capítulo**. Vive en la pantalla de inicio de Android (widget)
o flotando sobre tu escritorio (web), sin notificaciones.

```
Android (app + widget Glance) ──┐
                                ├──> FastAPI + SQLite ──> Claude (CLI con tu suscripción, o API key)
Web (Next.js: /admin + /widget) ┘          (todo en tu servidor local, un docker-compose)
```

## Qué hace

- **Alta de libro**: título (+ autor). Claude devuelve el índice de capítulos y el género, y se
  **pregeneran una sola vez** los pre-spoilers de todos los capítulos en 3 niveles
  (☁️ niebla · 🕯️ pista · 🔥 peligro). Cambiar de capítulo o de nivel después no gasta tokens.
- Junto a los pre-spoilers se pregenera una **ficha visual** por capítulo (nudo, desenlace y 3–5 elementos
  dibujables: objetos, animales, lugares, personajes), editable en el panel.
- **¿En qué capítulo vas?** Eliges el capítulo que terminaste (número o lista con títulos) y se genera un pixel
  art del **capítulo siguiente**: un elemento de su ficha, distinto de los ya dibujados (32×32, hasta 16 colores,
  2–3 frames de animación idle, estilo orgánico con luz y sombras).
- **Leyenda sellada**: el widget la muestra cifrada hasta que pulsas «Romper sello».
- **Terminé ✓** en el widget avanza el capítulo sin abrir la app.
- **Compartir** una tarjeta PNG (sellada o revelada) · **tema por género** (colores y paleta del personaje).
- Optimización de tokens: bloques de 10 capítulos por llamada, caché global por libro y reintento solo de lo
  inválido; con API key, además, prompt caching del contexto del libro y Message Batches API (−50 %).

## Claude sin API key (suscripción)

Por defecto (`CLAUDE_BACKEND=cli`) el backend ejecuta el CLI oficial de Claude Code en modo headless
(`claude -p --json-schema …`) autenticado con tu suscripción Pro/Max/Team. No se paga por token: cada alta de
libro (≈ 1 + capítulos/10 llamadas) y cada sprite consumen de los límites de uso de tu plan (ventana de 5 h y semanal).

- **Backend en el host** (`uv run uvicorn …`): basta con tener el CLI instalado y con sesión iniciada
  (`claude auth status`).
- **Backend en Docker**: la imagen ya trae el CLI. Genera un token de 1 año con `claude setup-token` (abre el
  navegador) y ponlo en `backend/.env` como `CLAUDE_CODE_OAUTH_TOKEN=…`.
- Úsalo para ti: los términos de Anthropic no permiten ofrecer el acceso de tu suscripción a otros usuarios en un
  producto propio. Si abres el widget a más gente, pasa a `CLAUDE_BACKEND=api` con `ANTHROPIC_API_KEY`.
- El modo `cli` ignora `USE_BATCH` (la Batches API solo existe con API key) y quita `ANTHROPIC_API_KEY` del
  entorno del CLI para que no tape tu login.
- Tiempos medidos (Sonnet, `CLAUDE_CLI_EFFORT=low`): alta de un libro de 3 capítulos ≈ 30 s; cada sprite
  ≈ 30–50 s (el cambio de capítulo espera al sprite). Con el esfuerzo por defecto del CLI el dibujo es más
  detallado, pero un sprite tardó ~3,5 min.

## Puesta en marcha (servidor local)

```bash
cp backend/.env.example backend/.env   # CLAUDE_CODE_OAUTH_TOKEN (o ANTHROPIC_API_KEY) y ADMIN_TOKEN
cp web/.env.example web/.env           # mismo ADMIN_TOKEN, ADMIN_PASSWORD y SESSION_SECRET (≥ 32 caracteres)
docker compose up -d --build
```

- Panel web: <http://localhost:3000> (entra con `ADMIN_PASSWORD`).
- API: <http://localhost:8000> (la app Android habla directamente con ella).
- En **Ajustes → Dispositivos** crea un token por dispositivo (se muestra una sola vez).

### Widget flotante en el escritorio

`/widget` → «Anclar encima de todo» usa Document Picture-in-Picture (Chrome/Edge ≥ 116) y **solo funciona
sobre HTTPS o `localhost`**. Para usarlo desde otro equipo de la red, sirve el panel por HTTPS
(p. ej. `tailscale serve` o Caddy). Sin soporte PiP (Firefox/Safari) hay dos alternativas: instalar `/widget`
como app (PWA) o activar el widget flotante dentro del panel en Ajustes.

### App Android (autónoma, sin servidor)

La app Android ya **no necesita el backend**: todo ocurre en el móvil y la única dependencia externa es una
clave gratuita de [Groq](https://console.groq.com/keys), que se pide en el primer arranque.

```bash
cd android && ./gradlew installDebug
```

- **Libros con texto real**: búsqueda en Project Gutenberg (Gutendex) y Wikisource, o tu propio EPUB sin DRM.
  El EPUB se separa en capítulos con su índice (sin IA).
- **Leyendas**: algoritmos clásicos sobre el texto (TF-IDF, TextRank, MMR; nunca se cita el último cuarto del
  capítulo) eligen el pasaje, y Groq lo convierte en una leyenda de 30–50 palabras. Un validador rechaza lo que
  invente y, si falla Groq o no hay red, se muestra la cita. Solo se narran el capítulo siguiente y el de después.
- **Dibujos**: iconos de [game-icons.net](https://game-icons.net) (CC BY 3.0) elegidos por las palabras del
  capítulo, pixelados a 32×32 y coloreados con la paleta del género (`poc/build_icons.py` los prepara).
- **Widget** (Glance): personaje, leyenda sellada (tocar para leer), niebla/pista/peligro y «Terminé».
  Se adapta al tamaño real y ajusta el texto al hueco disponible.

`poc/` contiene los scripts con los que se probó cada pieza (extracción de capítulos, algoritmos, comparación de
modelos para el narrador).

## Desarrollo

```bash
# Backend (46 pruebas, sin gastar tokens: usan un Claude falso)
cd backend && uv sync && uv run pytest

# Servidor de desarrollo SIN API key (datos de ejemplo) para probar web y Android
ADMIN_TOKEN=dev USE_BATCH=false uv run uvicorn dev_fake_server:app --port 8000

# Web
cd web && npm install && npm run lint && npm run build
```

## Estructura

```
backend/   FastAPI · sqlmodel · anthropic/CLI claude · Pillow  (app/services = Claude, pregeneración, sprites, tarjetas)
web/       Next.js 16 + Tailwind · BFF /api/* → FastAPI (el token maestro nunca llega al navegador)
android/   Kotlin · Compose · Glance (widget) · WorkManager
```

La fuente pixel de las tarjetas es *Press Start 2P* (SIL OFL 1.1).
