# PoC: libro libre → capítulos reales → pre-spoilers leyendo el texto

Un solo script, sin dependencias (Python ≥ 3.9). No usa el backend ni Claude.

```bash
python3 poc/packgen.py search "quijote" --lang es     # 1. Gutendex (Project Gutenberg)
python3 poc/packgen.py chapters 2000                  # 2. EPUB → índice real, sin IA
GEMINI_API_KEY=... python3 poc/packgen.py pack 345 --max 3   # 3. Gemini lee cada capítulo → poc/packs/
```

- Clave gratis: <https://aistudio.google.com/apikey> (cuota de quien la crea). Modelo: `GEMINI_MODEL`
  (por defecto `gemini-flash-latest`).
- `pack` es reanudable: si se agota la cuota guarda lo hecho y la siguiente ejecución sigue donde quedó
  (`--max 0` = todos los capítulos, `--delay` = segundos entre llamadas).
- La clave del pack es el QID de Wikidata **de la obra** (Gutenberg ID → P2034 → P629), igual para cualquier
  edición o repositorio.
- Los preliminares (tasa, prólogo, «al lector»…) se detectan y no se generan; las partes se anteponen al título
  («PRIMERA PARTE · I.»).

Probado sin IA: *Drácula* (27 + nota), *Don Quijote* (128), *Crimen y castigo* (40, con partes), *La Odisea* (24 cantos),
*Cuentos de amor* (44 cuentos).
