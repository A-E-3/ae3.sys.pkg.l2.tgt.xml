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

- Requires: `ae3.sdk`
- Provides: `ae3.pkg.tgt.xml`, `ae3.sys.l2.tgt.xml`, `ae3.sys.pkg.tgt.xml`, source-processes `compile-java` and `ae3-packages`, and a `java` classpath export.

## Gotchas

- No automated tests — `TestXml` is a manual harness only, run it directly to eyeball rendering output.
- Two distinct "package" concepts live in this one unit: the Java target-context (built via the `compile-java` source-process) and the AE3 runtime package `ae3.sys.l2.tgt.xml` under `ae3-packages/` (built via the `ae3-packages` source-process, its own `package.json`). Don't conflate the two when navigating or changing build wiring.
- The `skin-standard-xml` stylesheets (`layout.xsl.tpl` etc.) are genuine XSLT 1.0 — but as of now they're only ever applied client-side, by the browser, via a `<?xml-stylesheet?>` PI that `WebContextXml` (in `ae3.sys.pkg.i3.web`) embeds in the XML reply. Nothing in the Java code loads and runs these `.xsl` files server-side yet; see `../ae3.sys.pkg.i3.web/CLAUDE.md` and `../ae3.sdk/CLAUDE.md` for the pieces involved in adding that.
- `.xsl.tpl` files are wrapped in ACM.TPL directives (`<%FINAL: 'text/xml' %><%FORMAT: 'xml' %> ... <%/FORMAT%><%/FINAL%>`), which normally means the file goes through AE3's own template/execution engine (`ae3.sdk-lang.acm-tpl`, a bytecode-compiled scripting language, not plain text substitution) when served over HTTP. But `show.xsl.tpl` specifically has exactly these 4 directive tags and nothing else dynamic (verified: `grep -c '<%'` on the file returns 2 matched pairs, spanning the entire 2652-line body) — so its content is 100% static XSLT. For server-side use, the wrapper tags can just be string-stripped from the head/tail rather than running the file through the ACM.TPL engine.
