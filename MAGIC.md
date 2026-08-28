# MAGIC.md — ae3.sys.pkg.l2.tgt.xml

## For keeper-ae3 / magic-tester

`java/ru/myx/ae3/l2/xml/` holds the XML/XHTML render targets: `WebContextXml` (the historic
explicit-`___output=xml` handler, always `TargetMode.CLONE`, raw/unrendered by design),
`WebContextXmlXhtml` (unconditional server-side-XSLT-rendered `application/xhtml+xml`, ignoring
Accept) and `WebContextXmlAutoDetect` (the same server-side render, but Accept-gated — falls back
to `WebContextXml`'s raw reply when the client didn't ask for `application/xhtml+xml`), plus
`XslServerRender` (package-private, shared server-side XSLT rendering used by both subclasses).

**`WebContextXmlXhtml`/`WebContextXmlAutoDetect` and their `xhtml.json`/`auto-detect.json` wiring
are already-committed, pre-existing infrastructure, not new work from any current epic** —
`git log --oneline`: `1253122` "first attempts to render XSLT server-side", `324c3a2` "WebContextXml
moved in from i3.web; register xml/xhtml/auto-detect via JSON".
`ae3-packages/ae3.sys.l2.tgt.xml/settings/system/l3/targets/{xhtml,auto-detect}.json` carry no
uncommitted changes — any request whose Accept header carries `application/xhtml+xml` already
reaches `WebContextXmlXhtml.getResultReply()` in this working tree today, independent of the
`WebContextType` dispatch-race fix (see `ae3.sys.pkg.i3.web`'s own MAGIC.md) — neither design
considered for that fix touches `xhtml.json`.

The real uncommitted work on top of that already-landed foundation is confined to 4 files
(`git diff --stat`: 278 insertions across `WebContextXml.java`, `WebContextXmlAutoDetect.java`,
`WebContextXmlXhtml.java`, `XslServerRender.java`): `XslServerRender`'s `RenderException` loud-fail
behavior throughout, `WebContextXmlXhtml`'s styled-AE3-error-template fallback on render failure,
`WebContextXmlAutoDetect`'s Accept-gated upgrade with its silent-degrade-on-failure preserved by
design, and `WebContextXml`'s own new constructor seam (below).

**`WebContextXml`'s public two-argument constructor is unchanged; a new package-visible
three-argument constructor is the real addition** (`WebContextXml.java:28-40`): the public
constructor now delegates to it with `TargetMode.CLONE` (its own resolved behavior —
`TargetMode.CLONE`, same `query` assignment — is byte-for-byte unchanged), while
`WebContextXmlXhtml` and `WebContextXmlAutoDetect` call the package-visible constructor directly
with `TargetMode.CLONE_SKINNED` instead, to get the skin-driven walk their own `getResultReply()`
implementations rely on (so a nested `data-view` layout gets reduced to the `xml`+`xsl` sentinel
both subclasses look for). Additive and behavior-preserving — no existing caller's behavior
changed.

`XslServerRender.acceptsXhtml` (`XslServerRender.java:36-39`) gates on the literal substring
`"application/xhtml+xml"` in the `Accept` header — a bare `*/*` doesn't contain it, so even a
request that does reach `WebContextXmlAutoDetect` (AE3's own tier-4 dispatch wildcard, see
`ae3.sys.pkg.i3.web`'s own MAGIC.md) falls through to `WebContextXml`'s raw reply for that reason
alone, byte-identical to plain `WebContextXml`'s own output despite being a different class.

`show.xsl.tpl` (owned by `keeper-acm`, permanently off-limits to edit) fails to compile under the
JDK's bundled XSLTC on a union-of-filter-expressions XPath idiom — confirmed live: an
`Accept: application/xhtml+xml` request against `WebContextXmlXhtml` returns a real `500`
(`"Server-side XHTML render failed"`, `WebContextXmlXhtml.java:66-78`'s styled-AE3-error-template
recovery path). Three not-yet-decided options for working around this without touching
`show.xsl.tpl` itself: ship the dispatch-race fix alone for now (ordinary pages keep serving raw
XML), an in-memory XSLT text respelling inside `XslServerRender`'s own code before compiling (not
yet designed), or a JVM-wide `TransformerFactory` engine swap (e.g. Saxon — no such jar exists in
this workspace today).

**`show.xsl.tpl`'s own compile failure poisons all four `skin-standard-xml` templates at once, live
— confirmed 2026-08-26, not previously known.** `XslServerRender.transform` (`XslServerRender.java:70`)
looks up a compiled `Templates` object via `xslTemplates.get()`
(`SupplierVfsFolderXslTemplatesCached`, `ae3.sys.pkg.base`'s own
`java/ru/myx/ae3/util/fn/SupplierVfsFolderXslTemplatesCached.java`), keyed by the requested `xsl`
file name. That cache is built eagerly and all-at-once: `SupplierVfsFolderMapCached.checkReload`
(`ae3.sys.pkg.base`, same package) iterates every `*.xsl.tpl` file in `skin-standard-xml` in one
pass and calls `runDescriptorMapper` (which strips the ACM.TPL bookend and compiles via XSLTC) on
each in turn — with no per-file try/catch. `show.xsl.tpl`'s own known `TransformerConfigurationException`
(union-of-filter-expressions XPath idiom, see below) is thrown from inside that loop and propagates
straight out of the whole `.get()` call, uncaught, for **every** key — so a request naming
`showState.xsl` (independently confirmed XSLTC-compile-clean, see the XSLTC compile test below)
fails exactly the same way as one naming `show.xsl` itself, purely because `show.xsl.tpl` is present
in the same scanned folder. Confirmed against real log output (`XML-XSL:EXCEPTION` entries under
`path.private/logs/default/`, `ru.myx.ae3.properties.log.level=DEBUG`): a request for
`showState.xsl` logs the identical `show.xsl.tpl` stack trace
(`FilterExpr.typeCheck`/`UnionPathExpr.typeCheck`/`TypeCheckError`) as a request for `show.xsl`
itself. **Positive-control proof, not just inference**: the exact same test page (`magic-tester`'s
`ae3-test-render-ok.local`, `xsl: showState.xsl`) renders a real, correct, visibly-different
`application/xhtml+xml` body once `show.xsl.tpl` alone is excluded from the scanned folder (a
read-only overlay change in an isolated test copy — the real file on disk is never touched) — proving
the render mechanism itself, and `showState.xsl` itself, both work; only `show.xsl.tpl`'s own
presence in the shared scan blocks them. `SupplierMapAbstractCached.get()`
(`ae3.sys.pkg.base/java/ru/myx/ae3/util/fn/SupplierMapAbstractCached.java:33-78`) re-attempts this
same all-or-nothing scan on every request more than 2.5s apart (`this.lastDate + 2500L`), so this is
a stable, reproducible characteristic of the current tree, not a one-off caching artifact. Full probe
methodology and results: `unit-test/magic-tester/README.md`; dispatch-level (as opposed to
render-level) confirmation: `ae3.sys.pkg.i3.web`'s own MAGIC.md.

**The `<%FINAL%>`/`<%FORMAT%>` ACM.TPL bookend's real, limited hand-strip shortcut** (2026-08-26,
`skin-standard-xml/*.xsl.tpl`, confirmed via the standalone `.tpl`-render harness — technique in
`keeper-ae3.armed.md`'s own Domain knowledge): the four skin `.xsl.tpl` files here carry exactly
one dynamic TPL construct each, a bookend at top/bottom, in two distinct shapes —
`showAuth.xsl.tpl`/`showState.xsl.tpl` carry `<%FINAL: 'text/xml' %>` ... `<%/FINAL%>` only;
`show.xsl.tpl`/`layout.xsl.tpl` carry `<%FINAL: 'text/xml' %><%FORMAT: 'xml' %>` ...
`<%/FORMAT%><%/FINAL%>`. Only the `FINAL`-only shape is a safe hand-strip: real harness-rendered
output for `showAuth.xsl.tpl`/`showState.xsl.tpl` is byte-for-byte identical to literally deleting
the bookend markers and leaving the rest untouched (confirmed via `diff`, zero differences). The
`FINAL`+`FORMAT:'xml'` shape is **not** a passthrough — `FORMAT:'xml'` genuinely re-parses the
content as XML and re-serializes it, collapsing insignificant whitespace between tags (a verbatim
text run, e.g. an embedded `<script>` block, keeps its own original line breaks) — real rendered
output is substantially smaller than a naive bookend-strip: `show.xsl.tpl` 105,228 naive-stripped
bytes vs. 91,253 real-rendered bytes; `layout.xsl.tpl` ~4,037 vs. 3,701. This does not change the
XSLTC finding two paragraphs above: the human-owner independently re-ran the real harness-rendered
`show.rendered.xml`/`layout.xsl.tpl.rendered` through the same XSLTC compile check —
`show.rendered.xml` still fails with the identical failure signature (same `cellColumn/@id`
union-of-filter-expressions, same `FilterExpr.typeCheck`/`UnionPathExpr.typeCheck` chain),
`layout.xsl.tpl.rendered` compiles clean — so "only `show.xsl.tpl` fails" still holds; a naive
bookend-strip of `show.xsl.tpl` alone would have gotten the same pass/fail conclusion by luck, not
by using the real rendered text.

**Option (b) (in-memory XSLT respelling) design+proof pass, 2026-08-26 — a per-site respelling was
found and validated, but a second, unresolved XSLTC internal bug blocks a full compile.** Two more
union-of-filter-expressions sites exist beyond the two originally documented (`show.xsl.tpl` raw
lines 490, 1092), masked only by XSLTC's fail-fast typeCheck. A respelling idiom (split the union
into an `xsl:choose`/`xsl:when` or `xsl:if`-guarded branch pair, never bracket-filtering the
context variable itself) compiles clean per-site in isolation, but fixing the two sites that share
the `list` named template together triggers a separate, spurious `Variable or parameter
'parentInputValue' is undefined` XSLTC failure on an untouched, pre-existing reference — most
likely a per-compiled-method XSLTC resource limit, not a wording problem. No compiling whole-file
candidate reached this pass; the human-owner's own browser-equivalence acceptance criterion was
therefore not attempted. Full detail, isolation steps, and harness:
`ae3-interfaces.backlog.md`'s own `Context Facts`/`Context Gaps` (the show.xsl.tpl-handling
`INQUIRY` item), not duplicated here.

**Phase 0 live-verification of `MakeDataViewReplyFn.js`'s `?___output=pdf|txt|html|xls`
reductions, 2026-08-26 — `txt`/`html` confirmed working end-to-end; `pdf`/`xls` both fail, root
causes traced into their own repos (`ae3.sys.pkg.l2.tgt.pdf`/`ae3.sys.pkg.l2.tgt.xls` own
`MAGIC.md`), neither touched.** New test page (`unit-test/magic-tester/testpages/`:
`RenderDataViewShare.js` + `ae3-test-dataview.local.json`) calls the real
`MakeDataViewReplyFn.js` with a real `{fields,values}` layout (one plain field, one
`variant:"link"` field). Full detail: `ae3-interfaces.backlog.md`'s own `Context Facts`.

**Saxon-HE engine swap landed (2026-08-27) — `show.xsl.tpl` now compiles; a real Saxon
serializer-escaping defect found and fixed in the same pass, Java-only, `show.xsl.tpl` untouched.**
The JVM-wide `TransformerFactory` engine swap the paragraph above flagged as an undesigned option is
now real: `ae3.pkg.lib.util.saxon-he/jars/Saxon-HE-9.8.0-15.jar` is vendored, and new class
`java/ru/myx/ae3/l2/xml/SupplierVfsFolderXslTemplatesCachedSaxon.java` compiles `*.xsl.tpl` templates
with `new net.sf.saxon.TransformerFactoryImpl()` instead of the JDK's bundled XSLTC (XSLTC's static
type-checker cannot compile `show.xsl.tpl`'s union-of-filter-expressions XPath idiom; Saxon can).
`XslServerRender` now looks up `Templates` from that class. `show.xsl.tpl` itself is never modified —
only which engine compiles the same unmodified stylesheet text.

A real human-owner-mandated equivalence proof (real captured production XML, the same regenerated
stylesheet, Saxon output vs. `xsltproc`/libxslt as a disclosed real-Safari-automation substitute)
found one substantive divergence, not cosmetic: inside inline `<script>` blocks, Saxon emitted
`&amp;` where libxslt (and this stylesheet's own 20+ years of real-browser rendering) emit a bare,
unescaped `&` — `show.xsl.tpl:176`'s `'&amp;'` JS string literal is itself just an XML-escaped
single `&` character in the .tpl source, meant to reach the browser unescaped. Root-caused by reading
Saxon-HE 9.8.0-15's own shipped sources
(`ae3.pkg.lib.util.saxon-he/incoming/saxon-he-9.8.0-15-sources.jar`): `net.sf.saxon.serialize.HTMLEmitter`
(base of both `HTML40Emitter`/`HTML50Emitter`) decides whether text is inside a
`<script>`/`<style>` element - and therefore must be serialized unescaped, per the "html" output
method's own CDATA-content-model rule for those two elements - via a hardcoded
`elemName.hasURI("")` check, never accounting for the XHTML namespace, unlike the same base class's
own namespace-aware `isHTMLElement(NodeName)` (already `uri.equals("") || uri.equals(XHTML)` in
`HTML50Emitter`, used only for void-element handling). `show.xsl.tpl`'s root output element declares
`xmlns="http://www.w3.org/1999/xhtml"`, so every literal `<script>`/`<style>` element it emits
carries that namespace, not the empty one - so Saxon's own script/style CDATA-passthrough never
fired for this stylesheet at all.

Fixed in Java only, via Saxon's own documented per-`Configuration` extension point -
`SerializerFactory.newHTMLEmitter(Properties)`'s own javadoc: "This method exists so that it can be
overridden in a subclass." New class `SerializerFactoryHtmlScriptStyleFix.java` subclasses
`net.sf.saxon.lib.SerializerFactory`, returning a small subclass of whichever stock HTML4/HTML5
emitter Saxon would otherwise have picked (same `html-version` dispatch logic the stock method
itself uses) that re-tracks script/style nesting depth itself, triggered by local element name only
(case-insensitive, matching by name the same way every stock HTMLEmitter already does) — not gated
on namespace URI at all, since this class's only caller exists solely to render skin-standard-xml's
own XHTML-namespaced `.xsl.tpl` stylesheets. `SupplierVfsFolderXslTemplatesCachedSaxon`'s
`transformerFactory` field init now runs this registration
(`configuration.setSerializerFactory(new SerializerFactoryHtmlScriptStyleFix(configuration))`)
before any template gets compiled. The stock emitter's own `isHTMLElement()`/empty-tag handling is
untouched - this only changes which text gets Saxon's `DISABLE_ESCAPING` receiver option.

Re-verified against the same real captured-XML equivalence harness (byte-diffed, not asserted): the
`'&amp;'` → `'&'` divergence is gone, matching libxslt's own output exactly at that token, and a
full-document diff shows zero other `&amp;amp;`/double-escape artifacts anywhere. Only the two
already-accepted cosmetic categories remain (root-element namespace-attribute declaration order;
the first `<meta>`'s self-close style — libxslt leaves it void, Saxon still writes `</meta>` there,
unrelated to this fix and pre-existing either way), plus a separate, pre-existing, unrelated
`generate-id()` numbering divergence between Saxon's and libxslt's own internal id-generation
algorithms (confirmed present in the pre-fix output too — not introduced or touched by this change,
and implementation-defined per the XSLT spec, not a real bug).

**CORRECTION (2026-08-27, later same night) — the equivalence-vs-libxslt framing above was the
wrong acceptance criterion; `SerializerFactoryHtmlScriptStyleFix` reverted, real bug was live and
has since been fixed.** A live real-browser reproduction (`curl` with Safari's own real Accept
header, `application/xhtml+xml` response) found Safari's strict XML parser rejecting the response:
`xmlParseEntityRef: no name`, at the exact `'&'` token the equivalence pass above had accepted as
"fixed." Root cause of the *acceptance criterion*, not the code: libxslt/xsltproc was used as a
disclosed stand-in for a real browser's client-side XSLT engine, and its raw, unescaped `&`
HTML-output-method behavior is only valid when the actual result is served/parsed as `text/html`.
This server serves `WebContextXmlXhtml`'s output as `application/xhtml+xml`, which real browsers
parse as strict XML — where a bare `&` in character data is illegal unless escaped (`&amp;`) or
CDATA-wrapped. Saxon's *pre-fix* default behavior (plain `&amp;`) was already correct XML for this
content-type; `SerializerFactoryHtmlScriptStyleFix`'s CDATA-style zero-escaping made it invalid.

Fixed by reverting: `SupplierVfsFolderXslTemplatesCachedSaxon`'s static init now uses plain `new
TransformerFactoryImpl()` again, no custom `SerializerFactory`. `SerializerFactoryHtmlScriptStyleFix.java`
is unreferenced (left in place, not deleted — file removal was denied by the executing agent's
sandbox; harmless as dead code, nothing on the classpath calls it). No stylesheet change, same as
the original fix's own scoping.

Re-verified for real, three ways:
1. Standalone stock-Saxon harness (no custom `SerializerFactory`) against the real, current
   `show.xsl.tpl` and real captured production XML: `'&amp;'` (single-escaped, correct) — not `'&'`,
   not `'&amp;amp;'`.
2. Live server, real established tooling (`unit-test/magic-tester/verify-ae3-web-dispatch.sh`, both
   projects freshly recompiled into their real `bin/` — the ad hoc scratch scripts used earlier that
   night were retired in favor of this), targeting `Host: ae3.local` (the real production alias, not
   a synthetic testpage — `ae3.myx.nz.json` is itself just `{"type":"alias","alias":"ae3.local"}`),
   probed with the real Safari Accept header
   (`text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8`): `200`,
   `application/xhtml+xml; charset=UTF-8`, and the response body verified well-formed by both
   `xmllint --noout` and Python's `xml.etree.ElementTree` (status code alone is not sufficient
   evidence on this server — it returns real HTTP 200 even for its own internal error/message
   pages, so content was checked directly).
3. A second, isolated CDATA case: `show.xsl.tpl` has at least two different authoring shapes for
   literal script content — plain escaped-source text nodes (this bug's own reproduction, the
   `doConfirm` ternary) and `<script><![CDATA[...]]>` blocks with genuinely raw, unescaped `&&`/`<`
   in the `.tpl` source itself (e.g. the `Effects.Busy && new Effects.Busy(target)` block, not
   reachable through the welcome/index page this fix's other verification used) — output method is
   plain `method="html"`, no `cdata-section-elements`, so Saxon flattens CDATA content into an
   ordinary text node before serializing either way. A minimal isolated stylesheet reproducing that
   exact shape (XHTML-namespaced `<script><![CDATA[...&&...]]></script>`, `output method="html"`,
   stock Saxon) confirmed the same correct behavior: `&amp;&amp;` on output, well-formed. The fix is
   global at the serializer level (removes the broken override entirely) — no per-instance/per-line
   fix was needed once the wrong override was gone.

`unit-test/magic-tester/verify-ae3-web-dispatch.sh` itself gained two small, permanent fixes as
part of this pass (both real gaps the Saxon-HE engine-swap epic had left in this shared tooling, not
scoped to this one bug): its `build_classpath` was missing the Saxon-HE jar entirely (every
render would have thrown `NoClassDefFoundError` for `net.sf.saxon.TransformerFactoryImpl` before
this), and it now merges `ae3.sys.pkg.l2.tgt.xml`'s own live `resources/skin/*` on top of
`AE3_AXIOM_DIR`'s packaged copy (which goes stale between axiom rebuilds — the `itemZoom` static
error this same MAGIC.md documented elsewhere was reproduced live via this exact staleness before
the overlay fix landed). `merge_dir_entries` also switched `ln -sf` → `ln -sfn` (matching the
reasoning the retired `verify-show-xsl-fix-live.sh` scratch script already carried in its own
comment) so a directory-shaped merge entry actually replaces on collision instead of nesting.

## `test/ru/myx/ae3/l2/xml/TestXslTplCompile.java` — compiling the skin templates without a server

Compiles every `*.xsl.tpl` under the roots it is given through the same two steps the server's own
skin-template cache runs — `stripTplWrapper`, then
`new net.sf.saxon.TransformerFactoryImpl().newTemplates(...)` — and exits non-zero when any of them
fails. The server runs that compile lazily, on the first request needing the skin
(`SupplierMapAbstractCached.get` -> `SupplierVfsFolderMapCached.checkReload` ->
`SupplierVfsFolderXslTemplatesCachedSaxon.runDescriptorMapper`), so a stylesheet compile error is
otherwise reachable only as a live HTTP 500.

Run from this repo's own root, no build step (JDK single-file source launch):

```
java -cp ../ae3.pkg.lib.util.saxon-he/jars/Saxon-HE-9.8.0-15.jar \
     test/ru/myx/ae3/l2/xml/TestXslTplCompile.java
```

Roots may be named explicitly to check a built axiom's own packaged skin copy instead of these
sources. Exit status: `0` all compiled, `1` at least one did not, `2` the check's own controls failed
and its result means nothing. `--self-test` runs the controls alone.

Three controls run before any result is reported, so a clean report is never a check that merely
could not fail: a deliberately broken stylesheet must be rejected **and** must yield a captured
diagnostic; a sound one must be accepted; and the `stripTplWrapper` copy is compared against
`../ae3.sys.pkg.base/java/ru/myx/ae3/util/fn/SupplierVfsFolderXslTemplatesCached.java`, the only
git-tracked copy of that method. The drift guard fails closed — an unreachable or renamed original is
a failure, never a skip.

Saxon's own thrown `TransformerConfigurationException` carries no error text, no line and no file —
the diagnostics exist only in whatever `ErrorListener` is installed, and static stylesheet errors
arrive through `fatalError`, never `error`. A listener is installed for that reason. Warnings
(`SXWN9000`) still return a non-null `Templates` and are counted, not failed on. The file is read
with `new String(Files.readAllBytes(...), UTF_8)`, matching AE3's own VFS decode, which is lenient;
`Files.readString` is strict and would turn a servable file into an `IOException` instead of a
verdict. A UTF-8 BOM is deliberately not stripped — it survives `trim()` on the server too.

Nothing compiles this file: the distro `source-process:compile-java` stage compiles only a project's
own `java/` directory, and this repo's `.classpath` carries no `test` source entry. Adding one would
make Eclipse compile this class against a project classpath holding no Saxon jar. `test/` as the
source root also keeps the production siblings off the launcher's source path.

**`show.xsl.tpl` does not compile in this tree today** — two `XPST0008` static errors, line 1265
`parentInputValue` and line 2136 `itemZoom`, on the committed `cf0cf39` copy, byte-identical in every
checkout and in every built-axiom copy. Stripped-text line numbers equal file line numbers here,
since the ACM.TPL bookend is a single prefix on line 1. Not fixed: that file is off-limits.

`unit-test/magic-tester/verify-ae3-web-dispatch.sh` remains the deeper end-to-end probe, and is not a
substitute for this check: its `cmd_probe` counts only an unreachable endpoint as a failure, so a real
`500` prints in its table and the script still exits `0`.
