# Book Widget

## Purpose

Book Widget exists to make reading a book something you look forward to returning to. A small, playful pixel-art
character — inspired by the book you are currently reading — lives on your Android home screen (as a widget) or
floats over your desktop (on the web). It carries a short, mysterious caption (≤ 50 words) that **pre-spoils the
next chapter**, just enough to pull you forward without giving the story away.

The goal is to turn an unfinished book into a gentle, recurring invitation. Rather than nagging you with
notifications, the widget sits quietly in view and rewards each chapter you finish with a new drawing and a new
hint about what comes next. Curiosity does the work: you keep reading to find out what the next sealed caption
means, and to see the next piece of pixel art appear.

Every hint is anchored in the **real text** of the book, not improvised, so the character stays faithful to the
story and the nudges feel earned. In short, Book Widget is a quiet companion that keeps the next chapter
tantalizingly close and makes steady reading its own small reward.

## Architecture

```
Android (app + Glance widget) ──┐
                                ├──> FastAPI + SQLite ──> Claude (CLI with your subscription, or API key)
Web (Next.js: /admin + /widget) ┘          (everything on your own local server, one docker-compose)
```

## What it does

- **Adding a book**: title (and author). The chapter index is first looked up on **es.wikisource.org**
  (following its subpage hierarchy) to obtain the **real chapter count and names**; Claude is only used as a
  fallback when the book is not available there. The pre-spoilers for every chapter are **generated once** at
  three intensity levels (☁️ fog · 🕯️ hint · 🔥 danger). Switching chapters or levels afterward costs no tokens.
- When a book comes from a verified source, the pre-spoilers **and the visual sheet** are grounded in the
  **real chapter text**: a cheap pass summarizes it, and the captions are written from that summary (not from
  Claude's memory), avoiding fabrication.
- Alongside the pre-spoilers, a **visual sheet** is pre-generated per chapter (the turning point, the resolution,
  and 3–5 drawable elements: objects, animals, places, characters), editable in the admin panel.
- **Which chapter are you on?** You select the chapter you just finished (by number, or from a list with titles)
  and pixel art for the **next chapter** is generated: one element from its sheet, different from those already
  drawn (32×32, up to 16 colors, 2–3 idle animation frames, organic style with light and shadow).
- **Sealed caption**: the widget shows the caption encrypted until you tap "Break the seal."
- **Done ✓** on the widget advances the chapter without opening the app.
- **Share** a PNG card (sealed or revealed) · **theme by genre** (colors and character palette).
- Token optimization: blocks of 10 chapters per call, a global per-book cache, and retrying only what is
  invalid; with an API key, additionally prompt caching of the book context and the Message Batches API (−50%).

## Claude without an API key (subscription)

By default (`CLAUDE_BACKEND=cli`) the backend runs the official Claude Code CLI in headless mode
(`claude -p --json-schema …`), authenticated with your Pro/Max/Team subscription. There is no per-token charge:
each book added (source confirmation + summaries and spoilers in blocks; without a source, ≈ 1 + chapters/10
calls) and each sprite draw from your plan's usage limits (5-hour and weekly windows).

- **Backend on the host** (`uv run uvicorn …`): you only need the CLI installed and signed in
  (`claude auth status`).
- **Backend in Docker**: the image already includes the CLI. Generate a 1-year token with `claude setup-token`
  (opens the browser) and set it in `backend/.env` as `CLAUDE_CODE_OAUTH_TOKEN=…`.
- For personal use only: Anthropic's terms do not allow offering your subscription's access to other users in a
  product of your own. If you open the widget to more people, switch to `CLAUDE_BACKEND=api` with
  `ANTHROPIC_API_KEY`.
- The `cli` mode ignores `USE_BATCH` (the Batches API only exists with an API key) and removes
  `ANTHROPIC_API_KEY` from the CLI's environment so it does not override your login.
- Measured times (Sonnet, `CLAUDE_CLI_EFFORT=low`): adding a 3-chapter book ≈ 30 s; each sprite ≈ 30–50 s
  (the chapter change waits for the sprite). With the CLI's default effort the drawing is more detailed, but one
  sprite took ~3.5 min.

## Quick setup (local server)

```bash
cp backend/.env.example backend/.env   # CLAUDE_CODE_OAUTH_TOKEN (or ANTHROPIC_API_KEY) and ADMIN_TOKEN
cp web/.env.example web/.env           # same ADMIN_TOKEN, ADMIN_PASSWORD and SESSION_SECRET (≥ 32 characters)
docker compose up -d --build
```

- Web panel: <http://localhost:3000> (sign in with `ADMIN_PASSWORD`).
- API: <http://localhost:8000> (the Android app talks to it directly).
- In **Settings → Devices**, create a per-device token (shown only once).

### Floating desktop widget

`/widget` → "Pin above everything" uses Document Picture-in-Picture (Chrome/Edge ≥ 116) and **only works over
HTTPS or `localhost`**. To use it from another machine on the network, serve the panel over HTTPS (e.g.
`tailscale serve` or Caddy). Without PiP support (Firefox/Safari) there are two alternatives: install `/widget`
as an app (PWA) or enable the floating widget inside the panel under Settings.

### Android app

Requires Android Studio (or the Android SDK + JDK 17+):

```bash
cd android && ./gradlew installDebug
```

Open the app, enter the server URL (`http://YOUR-SERVER-IP:8000`) and the device token, then add the widget from
the home screen (long-press → Widgets → Book Widget). Resizable: 2×2 shows the character + "Done ✓"; 4×2 also
adds the sealed caption.

> The app allows HTTP because your server is on a private IP. Away from home, use Tailscale (with HTTPS).

## Development

```bash
# Backend (64 tests, no token or network usage: they use a fake Claude and fake sources)
cd backend && uv sync && uv run pytest

# Development server WITHOUT an API key (sample data) to test web and Android
ADMIN_TOKEN=dev USE_BATCH=false uv run uvicorn dev_fake_server:app --port 8000

# Web
cd web && npm install && npm run lint && npm run build
```

## Structure

```
backend/   FastAPI · sqlmodel · anthropic/CLI claude · Pillow  (app/services = Claude, pre-generation, sprites, cards)
web/       Next.js 16 + Tailwind · BFF /api/* → FastAPI (the master token never reaches the browser)
android/   Kotlin · Compose · Glance (widget) · WorkManager
```

The pixel font used on the cards is *Press Start 2P* (SIL OFL 1.1).
