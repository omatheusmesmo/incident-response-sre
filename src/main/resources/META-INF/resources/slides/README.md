# Slides — Agentic AI, Durable Workflows (Quarkus LangChain4j × Quarkus Flow)

reveal.js decks with **Markdown as the source of truth**, one file per language:

- `en.md` — English
- `pt.md` — Português

`en.html` / `pt.html` are thin reveal.js wrappers that load the matching `.md`; `index.html` is the language picker. Theme: **Tokyo Night** (`tokyo-night.css`), with matching code highlighting.

## Run

Served by the Quarkus app itself (static resources). Just start the app:

```bash
./mvnw quarkus:dev
```

- Home: <http://localhost:8080/> → links to the demo console and the slides
- Slides: <http://localhost:8080/slides/> (or `/slides/en.html`, `/slides/pt.html`)

No separate web server needed. (reveal.js and the theme CSS load from a CDN, so an internet connection is needed the first time.)

## Edit

Edit only `en.md` / `pt.md`. Slide separator: a line with `---`. Speaker notes: a line starting with `Note:`.
