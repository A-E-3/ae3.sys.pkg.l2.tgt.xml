# CLAUDE.md — ae3.sys.pkg.l2.tgt.xml

AE3 L2 media target for XML output. Renders `LayoutEngine` output as XML, optionally through an XSLT skin, as an alternative to HTML rendering.

## Structure

- `java/ru/myx/ae3/l2/xml/` — the target-context implementation.
  - `XmlFileTargetContext` — writes a rendered layout (`Xml.toXmlBinary`) to a file. This is the actual L2 target.
  - `WebContextXml`/`WebContextXmlXhtml`/`WebContextXmlAutoDetect` — the HTTP-facing `WebContext` adapters, see below. `WebContextXml` moved here from `ae3.sys.pkg.i3.web` (same session as the other two were added) — see this unit's Build section and `i3.web`'s CLAUDE.md for why.
  - `TestXml` — manual smoke-test entry point (`main`), not part of an automated suite. Renders `LayoutEngine.getDocumentation()` (or a `.jsld` file passed as `args[0]`) to a temp XML file and opens it via `Engine.createProcess`.
  - `about.jsld` — bilingual (en/ru) "about" text, surfaced through `LayoutEngine.getDocumentation()`.
- `ae3-packages/ae3.sys.l2.tgt.xml/` — a separate bundled AE3 runtime package (own `package.json`, `commonjs`, requires `ae3.base`) holding the rendering resources:
  - `resources/skin/skin-standard-xml/` — full skin: XSL templates (`layout.xsl.tpl`, `show.xsl.tpl`, `showAuth.xsl.tpl`, `showState.xsl.tpl`) plus JS reply-builder helpers under `resources/lib/ae3.l2.xml/helper/` (data form/table/view/message/sequence reply builders).
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

## Gotchas

- No automated tests — `TestXml` is a manual harness only, run it directly to eyeball rendering output.
- Two distinct "package" concepts live in this one unit: the Java target-context (built via the `compile-java` source-process) and the AE3 runtime package `ae3.sys.l2.tgt.xml` under `ae3-packages/` (built via the `ae3-packages` source-process, its own `package.json`). Don't conflate the two when navigating or changing build wiring.
- `.xsl.tpl` files are wrapped in ACM.TPL directives (`<%FINAL: 'text/xml' %><%FORMAT: 'xml' %> ... <%/FORMAT%><%/FINAL%>`), which normally means the file goes through AE3's own template/execution engine (`ae3.sdk-lang.acm-tpl`, a bytecode-compiled scripting language, not plain text substitution) when served over HTTP. But `show.xsl.tpl` specifically has exactly these 4 directive tags and nothing else dynamic (verified with plain `<%`/`%>` substring counts, not just a regex, to be sure none were missed: 4 and 4, both on the file's first and last line) — so its content is 100% static XSLT. `SupplierVfsFolderXslTemplatesCached` strips these tags itself rather than running files through the ACM.TPL engine, but only for files whose name ends in `.tpl` — a plain `.xsl` file is never stripped.
