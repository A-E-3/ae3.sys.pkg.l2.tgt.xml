# ae3.sys.pkg.l2.tgt.xml

AE3 L2 Media Target: XML / XML+XSL — renders layout output as XML, optionally through an XSLT skin, instead of HTML.

Requires: ae3.sdk, ae3.base, ae3.web
Provides: ae3.pkg.tgt.xml, ae3.sys.l2.tgt.xml, ae3.sys.pkg.tgt.xml

## What this does

Provides three HTTP output modes, selected by `___output=` or file extension:

- **`xml`** — raw XML with a client-side `<?xml-stylesheet?>` PI pointing at the XSL skin (`text/xml`)
- **`xhtml`** — server-side XSLT transform always (unconditional XHTML output)
- **`auto-detect`** (default) — server-side XSLT transform if the client's `Accept` header includes `application/xhtml+xml` and the result has an XSL skin; otherwise falls back to raw XML

The XSL skin (`skin-standard-xml`) transforms AE3 layout XML into HTML pages with flex layout, responsive CSS, and menu/form/table widgets.

## Key components

- `java/ru/myx/ae3/l2/xml/` — Java HTTP adapters (`WebContextXml`, `WebContextXmlAutoDetect`, `WebContextXmlXhtml`) and `XslServerRender` (cached XSLT template lookup)
- `ae3-packages/ae3.sys.l2.tgt.xml/resources/skin/skin-standard-xml/` — the main skin:
  - `show.xsl.tpl` — XSLT 1.0; renders all standard page layouts (forms, tables, lists, messages, menus)
  - `layout.xsl.tpl` — XSLT 1.0; renders API/documentation pages
  - `showAuth.xsl.tpl` / `showState.xsl.tpl` — auth and state-transition pages
  - `style.css.tpl` — CSS; flex page structure (`pg-*`), `zoom-*` layout modes, `hl-*` highlight classes, form/table styles
- `ae3-packages/ae3.sys.l2.tgt.xml/resources/lib/ae3.l2.xml/` — JS reply-builder library (`XmlSkinHelper`, lazy-loaded helpers for form/table/view/message/sequence replies)
- `settings/system/l3/targets/*.json` — dispatcher registration descriptors

## Skin CSS class conventions

CSS classes in `style.css.tpl` and XSLT in `show.xsl.tpl` share a prefix-family contract:

| Prefix | Purpose |
|--------|---------|
| `zoom-{zoom}-html/body` | Root layout mode (window / document) |
| `pg-*` | Flex page structure (north/main/south slots) |
| `tbar-dn/up` | Header / footer toolbars |
| `ui-*` | All UI components — tables, forms, menus, commands, badges |
| `hl-bn-{value}` | Row/item background color; driven by `@hl` or `@access` in data |
| `hl-hd-{value}` | Visibility (hidden/true); driven by `@hidden` in data |
| `hl-ui-{value}` | Display control; driven by `@ui` in data |
| `cell-tp-{type}` | Table cell type styling; boolean uses `[x-boolean]` attr selector |
| `el-radio`/`st-radio-*` | CSS-only radio/tab state machines (no JS) |
| `no-print` | Hidden in print media |

For full detail see CLAUDE.md.
