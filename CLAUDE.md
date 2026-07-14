# CLAUDE.md — ae3.sys.pkg.l2.tgt.xml

AE3 L2 media target for XML output. Renders `LayoutEngine` output as XML, optionally through an XSLT skin, as an alternative to HTML rendering.

## Structure

- `java/ru/myx/ae3/l2/xml/` — the target-context implementation.
  - `XmlFileTargetContext` — writes a rendered layout (`Xml.toXmlBinary`) to a file. This is the actual L2 target.
  - `WebContextXml`/`WebContextXmlXhtml`/`WebContextXmlAutoDetect` — the HTTP-facing `WebContext` adapters, see below. `WebContextXml` moved here from `ae3.sys.pkg.i3.web` (same session as the other two were added) — see this unit's Build section and `i3.web`'s CLAUDE.md for why.
  - `TestXml` — manual smoke-test entry point (`main`), not part of an automated suite. Renders `LayoutEngine.getDocumentation()` (or a `.jsld` file passed as `args[0]`) to a temp XML file and opens it via `Engine.createProcess`.
  - `about.jsld` — bilingual (en/ru) "about" text, surfaced through `LayoutEngine.getDocumentation()`.
- `ae3-packages/ae3.sys.l2.tgt.xml/` — a separate bundled AE3 runtime package (own `package.json`, `commonjs`, requires `ae3.base`) holding the rendering resources:
  - `resources/skin/skin-standard-xml/` — full skin. Declared abstract in `skin.settings.xml` (prototype: `skin-standard-html`, renderer: ACM.TPL, suffix `.tpl`). Contents:
    - XSL templates (all XSLT 1.0, `output=html`, wrapped in ACM.TPL `<%FINAL:'text/xml'%><%FORMAT:'xml'%>`):
      - `show.xsl.tpl` — main page skin. Variables: `$clean`, `$zoom` (window/document), `$standalone`, `$sudo`, `$base`, `$srot` (skin root), `$irot` (icons root). Flex page layout (`pg-root`/`pg-north`/`pg-gapc`/`pg-main`/`pg-south`). Handles menu (require.js), responsive inline styles for `$clean`, ae3 SPA modes (`client/@ae3='true'/'r'`). Named templates: `input-attributes`, `split-list` (tail-recursive comma-split), `formatted` (input type dispatch), `command`, `noscript-submenu`. Uses `disable-output-escaping` for `rawHeadData` injection.
      - `layout.xsl.tpl` — documentation/API skin. Matches `/xml` root; renders `article`/`method`/`object`/`field` elements as HTML doc with TOC and anchor links.
      - `showAuth.xsl.tpl` — authentication state pages: `authenticate`, `authentication-failed`, `authentication-success`, `authentication-logout`.
      - `showState.xsl.tpl` — state-transition pages: `done` (redirect), `updated`, `success`, `failed`.
    - `style.css.tpl` — main CSS (wrapped as `/* <%FINAL:'text/css'%><%FORMAT:'css'%><%= '/' + '*' %> */` — the `<%= '/' + '*' %>` closes the comment to avoid literal `/*` in ACM). Flex page layout classes, zoom modes, responsive breakpoints (559px/560px/800px/1280px), form/field/table styles.
    - `$files/` — static client assets: `dates.js`, `input-label-block-visibility.js`, `menu.css`, `favicon.ico`.
    - `layouts/` — layout interceptors (`.jso` files evaluated by the AE3 layout engine, `.jslt` for layout transforms):
      - `DataForm.jso`, `DataTable.jso`, `DataView.jso`, `Message.jso`, `MessageUpdateSuccess.jso`, `SelectView.jso`, `Sequence.jso` — each routes its layout type to the corresponding `XmlSkinHelperSingleton.make*Reply` call (for `zoom=document`; passes through otherwise).
      - `Xml.jslt` — CDN URI replacement: rewrites `layout.xsl` from the internal `show.xsl` path to the CDN-prefixed URI when `X-WebUI-CDN-URI` is present in the request.
    - `skin/` — HTTP error page templates (ACM.TPL, `%OUTPUT: body%` injection into skin-standard-html parent): `401.tpl` (login form), `403.tpl`, `404.tpl`, `500.tpl`, `xml.tpl` (XML-specific wrapper). These are **not** XSLT.
    - `skin-standard-xml.xml.tpl` — the skin descriptor (XML).
    - `skin.settings.xml` — skin configuration: abstract, prototype=skin-standard-html, renderer=ACM.TPL.
    - `checkShowIndex.xml.tpl`, `checkShowList.xml.tpl` — test/demo NDLS application fixtures (Network Device Licensing Server). Not part of the core skin; included as example usage of the XML+XSL skin with a real command index.
    - `checkSubmenu.html.tpl` — legacy HTML submenu test fixture with a hardcoded JS `submenu` array structure, used to test the menu JS integration.
  - `resources/lib/ae3.l2.xml/` — JS reply-builder library. Entry points: `helper.js` and `XmlSkinHelperSingleton.js` both export `new XmlSkinHelper()` (singleton). `XmlSkinHelper.js` is an `ae3.Class` extending `UiBasic`; all reply-builder methods use `execute:"once"` lazy-require to avoid eager loading.
  - `resources/skin/skin-standard-xml-clean/` — variant skin, no XSLT: "up reflectors/describers in XML" only (per its own `readme.txt`).

## Build

Eclipse Java project (`.project`/`.classpath`, `org.eclipse.jdt.core.javanature`), built through the AE3 monorepo's own dependency system via `project.inf`, not npm/maven/gradle:

- Requires: `ae3.sdk`, `ae3.base` (for `ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached`, see `ae3.sys.pkg.base`'s CLAUDE.md), `ae3.web` (for the `ru.myx.ae3.i3.web.WebContext` interface — `WebContextXml` itself now lives in this unit, package `ru.myx.ae3.l2.xml`, alongside its two XSLT variants)
- Provides: `ae3.pkg.tgt.xml`, `ae3.sys.l2.tgt.xml`, `ae3.sys.pkg.tgt.xml`, source-processes `compile-java` and `ae3-packages`, and a `java` classpath export.
- The `ae3.base`/`ae3.web` requires were added specifically for `WebContextXmlAutoDetect` (below) — this unit had no web-layer awareness before that; `XmlFileTargetContext`/`TestXml` are plain L2 target code, usable outside a web context entirely (e.g. writing to a local file). `.classpath` needs a `path="/ae3.sys.pkg.i3.web"` `classpathentry` for this too — it was missing for a while after the `Requires:` line was added (a real, confirmed gap: `project.inf` and `.classpath` had drifted apart), fixed alongside the `WebContextXml` move.

## Server-side XSLT rendering: three WebContextXml variants, all owned by this unit now

All in `java/ru/myx/ae3/l2/xml/`, package `ru.myx.ae3.l2.xml`, `WebContextXmlAutoDetect`/`WebContextXmlXhtml` both `extends WebContextXml` (same package, no import needed):

- **`WebContextXml`** — always the client-side `<?xml-stylesheet?>` PI + `text/xml`, ignores `Accept` entirely. Registered for `extensions: ["xml"]`, `contentTypes: ["application/xml", "text/xml"]` in `ae3-packages/ae3.sys.l2.tgt.xml/settings/system/l3/targets/xml.json` — used for explicit `___output=xml`/`.xml`. Moved here from `ae3.sys.pkg.i3.web` in the same pass that built the JSON registry (`WebContextOutputRegistry`) — before that it lived in the generic dispatch unit even though this unit is its only real "owner"; see `i3.web`'s CLAUDE.md for the fuller rationale (every concrete `WebContext*` class now lives with the target-context it wraps, not in the generic dispatcher).
- **`WebContextXmlAutoDetect`** — Accept-gated: for an `"xml"`-layout result with a non-empty `xsl`, if the request's `Accept` header lists `application/xhtml+xml`, renders server-side XHTML instead; otherwise (or on any failure) falls back to `super.getResultReply()` (plain `WebContextXml`'s pure behavior). Registered as the auto-detect default in `settings/system/l3/targets/auto-detect.json`, `extensions: ["*", "auto-detect"]` — `"*"` is `WebContextOutputRegistry`'s wildcard shortName, picked by `ae3.sys.pkg.i3.web`'s dispatcher whenever neither an explicit `___output` nor a recognized extension matched anything; `"auto-detect"` makes it independently selectable via `___output=auto-detect` too.
- **`WebContextXmlXhtml`** — unconditional: same as `WebContextXmlAutoDetect` but skips the `Accept` check entirely, always attempting the server-side XHTML render. Registered for `extensions: ["xhtml", "xhtm"]`, `contentTypes: ["application/xhtml+xml"]` in `settings/system/l3/targets/xhtml.json` — this is the *only* registrant for `xhtml`/`xhtm` now; the old DOM-based `WebContextXhtml` (moved from `i3.web` into `ae3.sys.pkg.l2.tgt.html`, alongside its own superclass) is no longer wired to that shortName at all, since server-side XSLT superseded it (that's the reason this whole feature exists).

Which concrete class gets constructed for a given request is resolved by `ae3.sys.pkg.i3.web`'s `WebContextOutputRegistry`, reading the `*.json` descriptors above from `/union/settings/system/l3/targets/` (see that unit's CLAUDE.md for the full dispatch order and descriptor shape) — no application/target-code edits or per-target override seam needed; registering a descriptor here is enough.

`XslServerRender` (package-private, same package) holds the actual cached `Templates` lookup + transform, shared by both variants rather than one inheriting from the other or a hook method — `acceptsXhtml(query)` and `transform(xsl, xml)`. The `Templates` cache itself: `SupplierVfsFolderXslTemplatesCached` (in `ae3.sys.pkg.base`) scans `Storage.UNION.relative("resources/skin/skin-standard-xml", null)` flat (no recursion) for every `*.xsl.tpl` file, keyed by public name (`.tpl` stripped, e.g. `show.xsl.tpl` -> `show.xsl` — matching what `result.xsl` actually ends with, since the ACM.TPL serving layer drops the `.tpl` suffix from public URLs). `/union` (not `/public`) so an override sitting in a higher-priority VFS tier is picked up, not just the base package resources.

## CSS class system (`style.css.tpl` ↔ `show.xsl.tpl`)

`style.css.tpl` and `show.xsl.tpl` are tightly coupled through a shared class vocabulary. Class names are structural contracts — XSLT writes them, CSS styles them, and JS sometimes reads them. They are **not** BEM or utility classes; they follow a family-prefix convention described below.

### Prefix families

**`zoom-*`** — set on `<html>` and `<body>` at the root template:
- `zoom-{zoom}-html` / `zoom-{zoom}-body` where zoom ∈ {window, document}
- `zoom-window-body`: `height:100%; overflow:auto` — full-viewport scroll
- `zoom-document-*`: centered document layout with white bg and `margin:auto`

**`pg-*`** — top-level flex page structure (only in non-`$clean` mode):
- `pg-root` — flex column, `justify-content/align-items:center`, `min-height:100%`
- `pg-north` — full-width header slot (contains `tbar-dn`)
- `pg-gapc` — flex spacer (1.3 ratio, min 66%)
- `pg-main` — main content (flex 2, min 83%) — contains `ui-document-out > ui-document-in`
- `pg-south` — full-width footer slot (contains `tbar-up`)
- `pg-main-root/gapc/main` — inner flex sub-layout within main

**`tbar-*`** — toolbar bars, all with `border:2pt solid #9ff; background:#eff; box-shadow`:
- `tbar-dn` — page header toolbar (bottom-rounded corners, placed in `pg-north`)
- `tbar-up` — page footer toolbar (top-rounded corners, placed in `pg-south`)
- `tbar` — generic non-rounded bar

**`ui-*`** — largest family; covers all UI components:
- *Page structure*: `ui-document-out`, `ui-document-in`, `ui-pagetitle` (single-line ellipsis title), `ui-clear` (clearfix), `ui-secondary` (0.66 opacity, fades in on hover), `ui-blk-caption` (0.5 opacity inline-block), `ui-pagebreak`
- *Commands/links*: `ui-cmd-icon` (float:left icon wrapper, 1.2em wide), `ui-cmd-text` (text beside icon), `ui-cmd-link` (block link, no underline), `ui-cmd-link-compact` (inline-block), `ui-cmd-link-cell`, `ui-cmd-link-group` (pipe-separated compact links via `::before`), `ui-cmd-preview-block` (indented), `ui-button` (styled button/span/a)
- *Table wrappers*: `ui-table-screen-{zoom}` (outer box-shadow frame), `ui-table-container` (horizontal scroll + decorative checkerboard bg), `table-list`/`table-view`/`table-edit`/`table-message` (float:left inner wrappers with inset shadow)
- *Table elements* (zoom-suffixed): `ui-list-table-{zoom}`, `ui-view-table-{zoom}`, `ui-edit-table-{zoom}`, `ui-message-table-{zoom}`, `ui-message-west-{zoom}`, `ui-message-east-{zoom}`, `ui-message-icon-{zoom}`
- *Map widget*: `ui-map`, `ui-map-{zoom}`, `ui-map-value`, `ui-map-index-{zoom}` — compact/cell zooms collapse index and key columns into stacked text
- *Form/field*: `ui-form-{zoom}` (on `<form>`), `ui-fldbox-{zoom}` (compact: collapsible animated span), `ui-fld-editor-textarea` (textarea for editor type), `ui-type-{type}` (on TD, e.g. `ui-type-boolean`)
- *Select/radio UI*: `ui-select-view` (on the select-view table div)
- *Sequence*: `ui-sequence` (custom element or div), `ui-sequence-item-compact`
- *Message widget parts*: `ui-wg-message-title/code/text/detail`
- *Menu*: `ui-menu-noscript` (hover-expand noscript menu), `ui-menu-ns-scrn` (zero-width until hover), `ui-menu-ctn-all` (see hl-ui below), `ui-menu-{zoom}`, `ui-menu-btn-ini` (hidden), `ui-menu-btn-btn`
- *Labels/badges*: `ui-label` (pill badge, 0.9em, blue #00A, max-width 17em + ellipsis), `ui-left` (float:left), `ui-right` (float:right), `ui-bold`, `ui-align-center`
- *Illustrations*: `ui-illustration`, `ui-illustration-central`, `ui-small`, `ui-medium`
- *Accordion*: `ui-chk-master` / `ui-chk-slave` — checkbox `:checked ~ sibling` reveal pattern
- *Misc*: `ui-hint` (cyan hint block), `ui-paragraph`, `ui-code`, `ui-code-{zoom}`, `ui-white-padding`/`ui-form-window2` (padded white boxes)

**`hl-*`** — highlight / visibility (two sub-families):
- `hl-bn-{value}` — background color, written via AVT. Two source attributes: `@hl` (list/view rows: `hl-bn-{@hl}`) and `@access` (command items: `hl-bn-{@access}`). Same CSS rules cover both:
  - `hl-bn-none` → #FAFAFA | empty/false/public/normal → #F0FFF7 (green) | admin → #FFF5F0 (red) | disabled → #F0F0F0 + opacity .55 | inactive → #F7F7F7 + opacity .75 | local/blue → #E7FAFF | true/user/attention → #F7FFF0 | priveleged/warn0 → #ffc | warn1 → #fec | error/warn2/`hl-ERROR` → #fdd | alert/`hl-CRITICAL` → #fbb
  - Standalone (not bn-prefixed): `hl-NORMAL` → #dfe | `hl-COLD` → #def | `hl-ATTENTION` → #ffd | `hl-MAYBE` → opacity .5
- `hl-ui-{value}` — visibility/size, driven by `@ui` attribute on command items (`hl-ui-{@ui}`):
  - `hl-ui-false` → `display:none` by default; revealed as `display:block` inside `DIV.ui-menu-ctn-all`
  - `hl-ui-true`, `hl-ui-` → visible (no-op by design)
  - `hl-ui-title` → font-size 120% | `hl-ui-small` → font-size 80%
  - `ui-cmd-link > .hl-ui-jump` → hidden by default; visible in `ui-menu-ctn-all`
- `hl-hd-{value}` — header/hidden rows, driven by `@hidden` attribute (`hl-hd-{@hidden}`). Only `hl-hd-true` has a CSS rule (opacity .6, display:none; revealed in `ui-menu-ctn-all`). `hl-hd-false` and `hl-hd-` are unstyled (visible by default).

**`cell-tp-*`** — column type class on TD, driven by `@type` attribute of column definition:
- `cell-tp-date` → `white-space:nowrap` | `cell-tp-number` → right-aligned, nowrap
- `cell-tp-boolean` — uses `[x-boolean='true'/'false']` attribute selector (not a sub-class) for color: true → green #171; false → red #711 + opacity .6

**`idx-box-*`** — command/index box containers by zoom: `idx-box-row`/`idx-box-cell` (block, overflow-x auto), `idx-box-compact` (inline-block). `idx-grp-cell` also appears in XSLT for group items but has **no CSS rule** — it is a class hook with no current styling.

**`no-print`** / **`no-print-margin`** — print-media only (`@media print`): `no-print` → `display:none`; `no-print-margin` → `margin/padding:0`. Applied to menu buttons and the `ui-menu-btn-ini` button.

**Form layout** (non-prefixed legacy names, on TD or DIV):
- `field` — form field wrapper (right-aligned, `padding:1pt 3pt`) | `fldkey` — label cell (right-align, width:1%) | `fldval` — value cell (left-align, fit-content) | `fldcollapse` — borderless collapsed row | `submit` — submit row (right-align, blue-tinted bg, box-shadow)
- `field-{zoom}` — zoom-specific field container (e.g. `field-compact`)

**`el-*` / `st-*`** — CSS-only state machines for radio/tab selects (no JS):
- `el-radio` — hidden `<input type="radio">` (`display:none`)
- `LABEL.el-radio` — visible label trigger
- `st-radio-tab` — tab-bar variant: `input:checked ~ #panel` reveals sibling by `~` combinator (XSLT emits inline `<style>` per-instance with the unique ID)
- `st-radio-sel` — select variant with custom radio circle drawn via `::before`
- `el-radio-tab-item` — hidden panel (`display:none`), revealed by `:checked ~`
- `el-radio-sel-item` — collapsed panel (height:0, visibility:hidden), transitions INPUT opacity on reveal

### XSLT↔CSS coupling rules

1. **Zoom propagation**: `$zoom` (window/document) flows from the root template down via `<xsl:param>`. Nested components downgrade: document/window → `row` item zoom; anything else → `compact`. The class suffix `ui-list-table-row` vs `ui-list-table-compact` is the result.

2. **Data-driven hl classes**: three XML attributes drive three class families via AVT — no XSLT branch logic: `@hl`/`@access` → `hl-bn-*` (background), `@hidden` → `hl-hd-*` (visibility), `@ui` → `hl-ui-*` (display). Unknown values silently no-op (no CSS rule matches).

3. **`x-boolean` attribute selector**: XSLT emits `<xsl:attribute name="x-boolean">true/false</xsl:attribute>` on the value div; CSS selects `[x-boolean='true']` / `[x-boolean='false']` for color. This separates presentation logic (CSS) from value extraction (XSLT) cleanly.

4. **`hl-ui-false` visibility gate**: XSLT outputs all items unconditionally; CSS hides `hl-ui-false` by default and reveals it only inside `ui-menu-ctn-all`. This is a CSS-controlled visibility gate with no XSLT branching.

5. **Custom elements**: `<ui-sequence>` and `<ui-sequence-item>` are emitted as literal XML element names (not `<div class="...">`); CSS styles them via element selectors (`ui-sequence`, `ui-sequence-item`). This works because `show.xsl.tpl` uses `output method="html"` which serializes unknown element names as-is.

6. **`$clean` mode inline styles**: When `$clean` is true, the XSLT injects an inline `<style>` block inside `<head>` overriding HTML/BODY padding/margin — this takes precedence over `style.css.tpl`'s rules without touching the CSS file.

7. **Compound column TD class**: `class="{@id} {@extraClass} cell-tp-{@type}"` — the column's own `@id` becomes a CSS class (e.g. `class="userId ..."` for a userId column), enabling per-column styling from outside the skin. `@extraClass` is the caller-supplied escape hatch.

## Gotchas

- No automated tests — `TestXml` is a manual harness only, run it directly to eyeball rendering output.
- Two distinct "package" concepts live in this one unit: the Java target-context (built via the `compile-java` source-process) and the AE3 runtime package `ae3.sys.l2.tgt.xml` under `ae3-packages/` (built via the `ae3-packages` source-process, its own `package.json`). Don't conflate the two when navigating or changing build wiring.
- `style.css.tpl` uses a non-obvious ACM.TPL wrapper: the file opens with `/* <%FINAL:'text/css'%><%FORMAT:'css'%><%= '/' + '*' %> */` — the `<%= '/' + '*' %>` string-concatenation produces `/*` in the output, which closes the outer CSS comment, hiding the ACM directives from CSS parsers. This is not a bug; it's intentional ACM escaping.
- `layouts/*.jso` files are layout-engine interceptors evaluated at request time, not static JSON — they contain JS with an `onLayoutExecute(context, layout)` function. `Xml.jslt` is a layout transform (`.jslt` extension) evaluated before layout execution.
- `checkShowIndex.xml.tpl` and `checkShowList.xml.tpl` are NDLS demo fixtures that happen to live in the skin package; they are not part of the skin infrastructure and should not be treated as authoritative examples of the skin's own XML schema.
- `.xsl.tpl` files are wrapped in ACM.TPL directives (`<%FINAL: 'text/xml' %><%FORMAT: 'xml' %> ... <%/FORMAT%><%/FINAL%>`), which normally means the file goes through AE3's own template/execution engine (`ae3.sdk-lang.acm-tpl`, a bytecode-compiled scripting language, not plain text substitution) when served over HTTP. But `show.xsl.tpl` specifically has exactly these 4 directive tags and nothing else dynamic (verified with plain `<%`/`%>` substring counts, not just a regex, to be sure none were missed: 4 and 4, both on the file's first and last line) — so its content is 100% static XSLT. `SupplierVfsFolderXslTemplatesCached` strips these tags itself rather than running files through the ACM.TPL engine, but only for files whose name ends in `.tpl` — a plain `.xsl` file is never stripped.

## XSLT patterns and gotchas (show.xsl.tpl)

- **Cyrillic `с` in `onсlick`**: the `с` in `onсlick` attributes throughout `command-bar` and confirm-button rendering is Cyrillic U+0441, not Latin `c`. This makes the attribute name unrecognised by browsers — a deliberate no-op. The real `onclick` handler is always set via the adjacent `<script defer>` block (`ready.push(...)` + `target.onclick = doConfirm.bind(...)`). Do not "fix" the spelling — the Cyrillic character is intentional.
- **`$depth | 2[not($depth)]`** default-parameter idiom: `select="$depth | 2[not($depth)]"` is the XSLT 1.0 way to provide a fallback value (`2`) when a param is empty/unset. The `2[not($depth)]` expression returns the number `2` only when `$depth` is empty (falsy).
- **`$iconWithTitle` result-tree-fragment variable**: several templates assign a tree fragment to a variable and then `<xsl:copy-of select="$iconWithTitle"/>` it in multiple places. This is the correct XSLT 1.0 pattern for local reuse; don't refactor to a named template unless the content needs to vary.
- **`<xsl:sort select="local-name() = 'appendix'"/>`** in the `documentation` template: sorts on a boolean expression — false (`''`) sorts before true (`'true'`), so non-appendix items come first. Standard XSLT 1.0 boolean-sort trick for "put appendices last".
- **Commented-out `<!-- xsl:choose /-->` block** in the `command` template: there is dead xsl:choose scaffolding left in XML comments (`<!-- xsl:when ... /-->`). The active code is the uncommented `<a href...>` element that follows.
- **`<xsl:comment> l: NNN ^ </xsl:comment>` line-number markers**: debug markers that emit HTML comments (e.g. `<!-- l: 421 ^ -->`) in rendered output. Harmless but visible in browser dev-tools.
- **View template emits two submit bars** when both `$describer` (the `fields` container) and `$format` (the outer layout element) independently carry `command`/`submit` children — each has its own `xsl:if` block. This is intentional: the describer can have field-level actions while the container has page-level actions.

## Frontend gotchas (show.xsl.tpl / style.css.tpl)

- **`tabindex2` / `onclick2` name-suffix disable pattern**: appending `2` to an attribute name is a deliberate inline commenting technique — the original name and value remain visible for context, but the attribute is inert (browsers ignore unknown attribute names). Used here to disable `tabindex` ordering and a redundant `onclick` while keeping the intent readable in the source.
- **`href="javascript:void"` (no `(0)`)** on `<a class="ui-cmd-preview-block">` anchors: `javascript:void` without `()` doesn't return undefined cleanly in all browsers. In practice these anchors are never navigated — their content is loaded via `Comms.createFrame` — but the href is technically wrong.
- **`seamless="seamless"` on `<iframe>`** in the `document-url` variant: `seamless` was removed from the HTML5 spec. It is ignored by all current browsers.
- **`--height:100%` custom property** declared on the `HTML` rule in `style.css.tpl` but never consumed via `var()` anywhere. Cost is zero (custom properties don't affect layout unless referenced). Likely aspirational/forgotten — leave it.
- **`zoom: 0.5 / 0.67 / 0.75 / 0.85`** on `IMG.ui-small` / `IMG.ui-medium`: the `zoom` CSS property is non-standard (Chrome/Safari only). Firefox overrides are provided via the `@-moz-document url-prefix()` hack using `max-width`/`max-height`. For fully standard scaling, `transform: scale()` would be correct — but the current approach works in all browsers the skin targets.
