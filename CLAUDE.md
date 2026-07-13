# CLAUDE.md — ae3.sys.pkg.l2.tgt.xml

AE3 L2 media target for XML output. Renders `LayoutEngine` output as XML, optionally through an XSLT skin, as an alternative to HTML rendering.

## Structure

- `java/ru/myx/ae3/l2/xml/` — the target-context implementation.
  - `XmlFileTargetContext` — writes a rendered layout (`Xml.toXmlBinary`) to a file. This is the actual L2 target.
  - `TestXml` — manual smoke-test entry point (`main`), not part of an automated suite. Renders `LayoutEngine.getDocumentation()` (or a `.jsld` file passed as `args[0]`) to a temp XML file and opens it via `Engine.createProcess`.
  - `about.jsld` — bilingual (en/ru) "about" text, surfaced through `LayoutEngine.getDocumentation()`.
- `ae3-packages/ae3.sys.l2.tgt.xml/` — a separate bundled AE3 runtime package (own `package.json`, `commonjs`, requires `ae3.base`) holding the rendering resources:
  - `resources/skin/skin-standard-xml/` — full skin: XSL templates (`layout.xsl.tpl`, `show.xsl.tpl`, `showAuth.xsl.tpl`, `showState.xsl.tpl`) plus JS reply-builder helpers under `resources/lib/ae3.l2.xml/helper/` (data form/table/view/message/sequence reply builders).
  - `resources/skin/skin-standard-xml-clean/` — variant skin, no XSLT: "up reflectors/describers in XML" only (per its own `readme.txt`).

## Build

Eclipse Java project (`.project`/`.classpath`, `org.eclipse.jdt.core.javanature`), built through the AE3 monorepo's own dependency system via `project.inf`, not npm/maven/gradle:

- Requires: `ae3.sdk`, `ae3.base` (for `ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached`, see `ae3.sys.pkg.base`'s CLAUDE.md), `ae3.web` (to extend `ru.myx.ae3.i3.web.WebContextXml`)
- Provides: `ae3.pkg.tgt.xml`, `ae3.sys.l2.tgt.xml`, `ae3.sys.pkg.tgt.xml`, source-processes `compile-java` and `ae3-packages`, and a `java` classpath export.
- The `ae3.base`/`ae3.web` requires were added specifically for `WebContextXmlAutoDetect` (below) — this unit had no web-layer awareness before that; `XmlFileTargetContext`/`TestXml` are plain L2 target code, usable outside a web context entirely (e.g. writing to a local file).

## Server-side XSLT rendering: three WebContextXml variants

All in `java/ru/myx/ae3/l2/xml/`, all `extends ru.myx.ae3.i3.web.WebContextXml`:

- **`WebContextXml`** (in `ae3.sys.pkg.i3.web`, unmodified) — always the client-side `<?xml-stylesheet?>` PI + `text/xml`, ignores `Accept` entirely. Used for explicit `___output=xml`.
- **`WebContextXmlAutoDetect`** — Accept-gated: for an `"xml"`-layout result with a non-empty `xsl`, if the request's `Accept` header lists `application/xhtml+xml`, renders server-side XHTML instead; otherwise (or on any failure) falls back to `super.getResultReply()` (plain `WebContextXml`'s pure behavior). Intended for the "default" (no explicit `___output`) rendering path.
- **`WebContextXmlXhtml`** — unconditional: same as `WebContextXmlAutoDetect` but skips the `Accept` check entirely, always attempting the server-side XHTML render. The explicit, forced counterpart to `___output=xml` (not currently wired to a `___output` value — `WebContextType`'s `HTTP_XHTML` already claims `"xhtml"` for the unrelated DOM-based `WebContextXhtml`, so adding one would need a different `___output` value or a deliberate remap).

Which concrete class actually gets constructed (in place of plain `WebContextXml`) for any given request is decided by application/target code outside this SDK — `WebContextType`/`WebTargetActor.apply()` (in `ae3.sys.pkg.i3.web`) is the only place that constructs a `WebContext`, has no per-target override seam, and this change deliberately doesn't touch it. The swap is a straightforward substitution wherever that application code currently references `WebContextXml` directly — both new classes share its exact public constructor shape `(TargetInterface, ServeRequest)`.

`XslServerRender` (package-private, same package) holds the actual cached `Templates` lookup + transform, shared by both variants rather than one inheriting from the other or a hook method — `acceptsXhtml(query)` and `transform(xsl, xml)`. The `Templates` cache itself: `SupplierVfsFolderXslTemplatesCached` (in `ae3.sys.pkg.base`) scans `Storage.UNION.relative("resources/skin/skin-standard-xml", null)` flat (no recursion) for every `*.xsl.tpl` file, keyed by public name (`.tpl` stripped, e.g. `show.xsl.tpl` -> `show.xsl` — matching what `result.xsl` actually ends with, since the ACM.TPL serving layer drops the `.tpl` suffix from public URLs). `/union` (not `/public`) so an override sitting in a higher-priority VFS tier is picked up, not just the base package resources.

## Gotchas

- No automated tests — `TestXml` is a manual harness only, run it directly to eyeball rendering output.
- Two distinct "package" concepts live in this one unit: the Java target-context (built via the `compile-java` source-process) and the AE3 runtime package `ae3.sys.l2.tgt.xml` under `ae3-packages/` (built via the `ae3-packages` source-process, its own `package.json`). Don't conflate the two when navigating or changing build wiring.
- `.xsl.tpl` files are wrapped in ACM.TPL directives (`<%FINAL: 'text/xml' %><%FORMAT: 'xml' %> ... <%/FORMAT%><%/FINAL%>`), which normally means the file goes through AE3's own template/execution engine (`ae3.sdk-lang.acm-tpl`, a bytecode-compiled scripting language, not plain text substitution) when served over HTTP. But `show.xsl.tpl` specifically has exactly these 4 directive tags and nothing else dynamic (verified with plain `<%`/`%>` substring counts, not just a regex, to be sure none were missed: 4 and 4, both on the file's first and last line) — so its content is 100% static XSLT. `SupplierVfsFolderXslTemplatesCached` strips these tags itself rather than running files through the ACM.TPL engine, but only for files whose name ends in `.tpl` — a plain `.xsl` file is never stripped.
