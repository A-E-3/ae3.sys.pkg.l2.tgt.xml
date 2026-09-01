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

**`SupplierVfsFolderXslTemplatesCachedSaxon` uses Saxon's native s9api (`Processor`/
`XsltCompiler`), not the JAXP `TransformerFactory`/`Templates` pair used elsewhere, and is
deliberately the only Saxon-dependent class in this package.** s9api is what lets
`XslServerRender` build its `SerializerXhtmlDisableEscapingNeutralizing`/character-map pipeline at
transform time. The Saxon dependency stays local to `ru.myx.ae3.l2.xml` rather than touching the
shared, generic `ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached` (JDK XSLTC, used
elsewhere) or any other `TransformerFactory` call site (e.g. acm-base-sdk's
`AcmXsltLanguageImpl`, a separate, untouched mechanism).

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

**The `data-table`/`data-form`/`data-view` layout names never reach `show.xsl.tpl` literally — a
JS-level rename happens first, confirmed by reading the transformation helpers.**
`ae3.sys.l2.tgt.xml/resources/lib/ae3.l2.xml/helper/Make*ReplyFn.js` rewrites `attributes.layout`
before producing XML: `MakeDataTableReplyFn.js:72` sets it to `"list"` (content wrapped in
`<list>`, or the reply's own `layout.rootName`, with `<columns>`/`<item>` children),
`MakeDataFormReplyFn.js:51` sets it to `"form"`, `MakeDataViewReplyFn.js:46` sets it to `"view"`;
`MakeSelectViewReplyFn.js:49` (`"select-view"`) and `MakeSequenceReplyFn.js:48` (`"sequence"`)
leave the name unchanged. Every one of these replies is then served with
`xsl: "/!/skin/skin-standard-xml/show.xsl"` — so this stylesheet's own
`*[@layout='list'|'form'|'view'|'select-view'|'sequence'|'formatted'|'rows'|'message']` templates
are the real rendering path for every `data-*` layout a caller (e.g. NDSS) sends. Grepping this
stylesheet for the literal string `"data-table"`/`"data-form"`/`"data-view"` will never match —
those names only ever exist on the JS-reply side, before this rename runs.

**Open, unresolved: a genuinely invalid nested test-content shape triggers unbounded recursive
template expansion and a real `OutOfMemoryError`; root cause not fully isolated.** Building test
content to exercise `show.xsl.tpl` (isolated to a test-only copy of the stylesheet — production
`show.xsl.tpl` never touched) produced a live `java.lang.OutOfMemoryError: Java heap space` from
unbounded recursion (`NamedTemplate.expand → CallTemplate.process → Choose.processLeavingTail →
Block.processLeavingTail → NamedTemplate.expand → ...`). Two contributing causes were found and
removed from the test content: nesting a full `<status layout="view">` root element inside an
unrelated table row's arbitrary child content (the generic `*[@layout='view']` template, line
~2153, has no apparent depth guard against this shape); and using bare
`<row><cell>...</cell></row>` children for a field's `variant="rows"` value, which collides with
the same tag names (`row`) used by a real top-level table structure elsewhere in the same document
(plausible template-matching ambiguity, not fully confirmed). **After removing both, a reduced
test — only scalar/text/date/geo field variants, no table, no `rows`-variant — still hung/OOM'd.**
Root cause for this remaining case is not isolated; this stays a genuinely open item, not resolved.
Next step, not yet performed: check whether the fabricated `select`/`map`/`sequence`/`list`
field-variant child shapes used in that test content (invented without checking real templates
first) match what this stylesheet's own named templates for those variants actually expect — the
real shapes for `sequence` etc. are now knowable from `MakeSequenceReplyFn.js` (see the
`data-*`-to-layout-name rename note above) and should be checked against `show.xsl.tpl`'s own
`sequence`/`select`/`map`/`list` named templates before assuming a fix.

**A second, real Saxon serializer-escaping defect found and fixed (2026-08-30), separate from the
script/style CDATA issue above.** Saxon-HE's `net.sf.saxon.serialize.HTMLEmitter` (base of
`HTML40Emitter`/`HTML50Emitter`) unconditionally serializes character U+00A0 (NO-BREAK SPACE) as
the literal named entity `&nbsp;` — hardcoded in `HTMLEmitter#writeEscape(CharSequence, boolean)`
(`else if (c == 160) { writer.write("&nbsp;"); }`). Confirmed by reading Saxon-HE 9.8.0-15's own
shipped sources (`ae3.pkg.lib.util.saxon-he/incoming/saxon-he-9.8.0-15-sources.jar`,
`net/sf/saxon/serialize/HTMLEmitter.java:377-379`). `&nbsp;` is only a legal XML name when a DTD
declares it; the DTD-less `application/xhtml+xml` this unit serves has none, so a real client-side
XML parser rejects it ("Entity 'nbsp' not defined") whenever U+00A0 reaches the serializer.
`show.xsl.tpl` itself already emits U+00A0 correctly, as the legal numeric reference `&#160;` —
XSLT compilation resolves that to the real character before Saxon re-serializes it, so the bug is
entirely in the serializer, not the stylesheet.

Fixed in Java only, via the same `SerializerFactory.newHTMLEmitter(Properties)` extension point
used (and reverted) above: new class `SerializerFactoryHtmlNbspFix.java` subclasses
`net.sf.saxon.lib.SerializerFactory`, returning a small subclass of whichever stock HTML4/HTML5
emitter Saxon would have picked, overriding `characters(...)` to intercept only U+00A0 and emit it
via the same numeric-character-reference mechanism (`&#xA0;`) Saxon's own `writeEscape` already
uses for other out-of-target-charset characters — every other character, and any content already
serialized with escaping disabled (e.g. real `<script>`/`<style>` CDATA passthrough), passes
straight through to Saxon's own stock `characters(...)` unchanged. `writeEscape` itself couldn't
be overridden directly: it is a single ~120-line method reading fields not visible outside
`net.sf.saxon.serialize`. `SupplierVfsFolderXslTemplatesCachedSaxon`'s static init now registers
this factory.

**CORRECTION (2026-09-01) — the CDATA-wrap mechanism below (`DisableEscapingNeutralizingReceiver`,
`SerializerXhtmlDisableEscapingNeutralizing`, and the client-side unwrap script) is removed.**
Pages are now served as `text/html` (fixed a separate menu bug), and under HTML5 parsing
`<![CDATA[...]]>` has no special meaning — it becomes a "bogus comment" that swallows content
unpredictably, breaking the DataTables script loading this mechanism existed to protect
(confirmed live: `ReferenceError: jQuery`, a stray literal `]]>` in the rendered page). Human-owner
decision: "NO CDATA NEEDED, HTML5" — HTML5 natively supports raw embedded `<script>`/`<style>`
blocks in server-rendered markup, unlike strict XML (the reason CDATA-wrapping existed at all), so
`disable-output-escaping="yes"` content (the DataTables `rawHeadData` blob) can now just pass
straight through raw/unescaped. `XslServerRender.transform` uses a stock s9api `Serializer`
directly — no custom `Receiver`, no CDATA-wrapping, no client-side reconstruction step; Saxon's own
`XMLEmitter` already emits `DISABLE_ESCAPING` content literally, which is exactly the desired
behavior once the output is HTML5, not XML. `input-label-block-visibility.js`'s
`initRawHeadDataUnwrap`/`rawHeadDataExtractBlocks`/`rawHeadDataParseAttrs`/`rawHeadDataActivateBlock`
removed accordingly — the raw blocks are already live in the initial HTML5-parsed DOM. The whole
history below (why the CDATA design existed, its single-block-regex predecessor, its four-block
bugfix) is kept for archaeology only; none of that code exists in the tree anymore.

**`XslServerRender.transform` prepends a minimal HTML5 doctype, and reports+throws on failure
instead of returning null.** `show.xsl.tpl`'s own `xsl:output` declares no doctype-system, so this
server-rendered output has none either — unlike every other reply this framework has served, which
resolved to a real `html`/`htm` `HtmlDomTargetContext` reply carrying its own doctype further up
the chain. A doctype-less document, if content negotiation ever lands it on the `text/html` parser
instead of the XML one, renders in quirks mode — a mode real production traffic has never actually
been in — so `transform` prepends `<!DOCTYPE html>` (valid, subset-free, legal preceding an XHTML
document same as an HTML one) to keep every reply through this path in standards mode
unconditionally. Failure handling was a bare `return null`: every failure (missing/uncompiled
stylesheet, malformed xml, transform error) silently fell back to `WebContextXml`'s plain text/xml
reply with zero trace of why, which made the "`?___output=xhtml` silently returns xml" symptom
impossible to root-cause. `transform` now reports it (same `Report.exception`/`Report.debug` idiom
used elsewhere, e.g. `SkinImpl`'s `SKIN-LOADER`) and throws `RenderException` instead of swallowing
it.

**`renderMessage`/`renderReplyIfNeeded` render a styled AE3 error/message page through `show.xsl`
instead of forwarding a reply unrendered.** `renderMessage` builds a
`<message layout="message" code="..." title="...">` XML document in the same shape
`MakeMessageReplyFn.js`'s `makeMessageReply(context, layout)` builds for a
`{layout:"message", reason, message, detail, code}` object (root element name, the
`layout`/`code`/`title` attributes, the unconditional `<reason>` child defaulting to "Unclassified
message.", the `<message debug="x-string" class="code style--block">`/`<detail ...>` children for
plain-string content), then renders it through "show.xsl" — the one local stylesheet whose root
template dispatches on `@layout` and has a `*[@layout='message']` template. `renderReplyIfNeeded`
returns a `ReplyAnswer` unchanged when it is already final, binary, a file, or a redirect (3xx) —
the exact same gates `ru.myx.ae3.skinner.SkinnerAbstract#handleReply`/`#handleReplyOnce` use to
decide a reply needs no further rendering, copied here verbatim since this is the same decision
applied to the skin-standard-xml family instead of skin-standard-html — otherwise it builds a new
`application/xhtml+xml` reply carrying the same code, attributes, and flags as the original, with a
body extracted the same way `SkinnerAbstract.handleReplyOnce` extracts title/body from a
textual/empty/exotic-object reply, rendered via `renderMessage`.

**`WebContextXmlXhtml`'s render-failure handling has two fallback tiers, both deliberate.** An
explicit `___output=xhtml` request was deliberately asked for, so a `RenderException` from
`XslServerRender.transform` surfaces loudly instead of silently degrading to the client-side-XSLT/
text/xml reply (that silent fallback is `WebContextXmlAutoDetect`'s job, for the implicit/default
path only) — rendered through the same real message-layout → show.xsl path
`Share.js`/`UiBasic.js`'s `makeServerFailureLayout` → `MakeMessageReplyFn.js`'s `makeMessageReply`
→ `show.xsl.tpl`'s `*[@layout='message']` template already uses for a genuine styled AE3 error
page, built synchronously via `renderMessage`, no exception thrown. If `show.xsl` itself is
unreachable/broken (the inner `renderMessage` call throws too), a last-resort hand-rolled HTML
error page is returned instead, kept independent of the rendering pipeline that just failed twice
so a reply is never left unanswered.

**Script/style raw blocks reaching `XslServerRender`'s `DisableEscapingNeutralizingReceiver` are
always CDATA-wrapped whole, unconditionally — no tag-detection/regex layer — and a client-side
script reconstructs the real elements from the CDATA text on page load (2026-08-31 fix, replacing
the single-block-regex design below).** The general fix plain-escapes any `disable-output-escaping`
content reaching this Java rendering path; that alone makes `rawHeadData`'s real production
DataTables init blob (built by `Ae3WebService.js`/`AcmWebService.js`'s `prepareHtmlTable()`, reached
via `DataTable.jso`'s `context.rawHtmlHeadData`) well-formed but permanently inert text, not a fix —
it's meant to become real, live, executing `<script>`/`<style>` elements.

An earlier version of this fix (kept here for history, since the same coupling-risk analysis still
applies below) special-cased a `characters()` event whose entire raw value was a single
self-contained `<script>...</script>`/`<style>...</style>` block, matched via an anchored regex
(`RAW_TAG_WRAPPER`), and CDATA-wrapped only the interior, keeping the outer tag text raw so the
element itself stayed live. **Found broken post-deployment (2026-08-31): the real `rawHeadData`
payload is never a single block** — `prepareHtmlTable()` concatenates **four** raw elements into one
`characters()` event (two library-loading `<script src="...">` tags for jQuery and
`jquery.dataTables.min.js`, one inline init `<script>`, one `<style>` block), which the anchored
single-block regex never matched, so the whole blob silently fell through to the plain-escaping
default — inert text, live-confirmed via `X-Debug-Origin: WebContextXmlAutoDetect` header evidence
on `ae3.myx.nz/monitoring/runtimeStatsLog`, a page that genuinely reaches `XslServerRender.transform`
(unlike `preview.ndss.knt9.xyz`'s equivalent NDSS page, whose `X-Debug-Origin: LAYOUT_FINAL` reply is
already final and short-circuits before this class is ever reached — that page's working DataTables
rendering goes through the plain client-side-XSLT/`text/xml` path instead, a real browser's own
XSLT engine having no single-block restriction). A human-owner design decision (two options
presented, `show.xsl.tpl` explicitly kept off-limits for edits — "No, keep it off-limits") settled
the real fix as: drop the regex/tag-detection layer entirely, always CDATA-wrap the whole raw value
(single block, four blocks, anything), and move the "make it live again" responsibility to the
client, since a CDATA section's content is delivered to the DOM as inert text regardless of what a
server-side serializer does — no server-side mechanism can make markup inside a CDATA section
execute, only a client-side script reading it back out can.

Saxon's own stock `cdata-section-elements` mechanism (`net.sf.saxon.serialize.CDATAFilter`,
confirmed by reading its source) still can't reach this content, for the same reason as before: it
keys off real `startElement`/`endElement` events on its own element stack and explicitly bypasses
itself whenever `DISABLE_ESCAPING` is set on a `characters()` event ("if the user requests
disable-output-escaping, this overrides the CDATA request") — this raw blob never generates real
element events for it to key off, arriving as one opaque, already-serialized string regardless of
how many blocks it represents. `DisableEscapingNeutralizingReceiver.characters()` now simply calls
`emitAsCdata` on the entire raw value whenever `DISABLE_ESCAPING` is set, full stop — the
open-tag-raw/interior-CDATA split, the anchored regex, and the plain-escaping fallback for anything
that didn't match are all gone; `emitAsCdata`'s own `"]]>"`-splitting logic (required by the CDATA
XML-spec regardless of design, unchanged) is the only "loop" left.

Coupling risk (flagged in review, still applies — arguably more central now, since the mechanism
fires unconditionally instead of on one narrow matched shape): this mechanism is correct only as
long as three pieces of undocumented Saxon-internal behavior hold — `ReceiverOptions.DISABLE_ESCAPING`'s
exact semantics, `CDATAFilter`'s own bypass-on-disable-escaping behavior, and
`CharacterMapExpander`'s pass-through of already-disabled-escaping events — none of which are part
of Saxon's public API contract. A Saxon version upgrade could change any of them with no
compile-time signal; re-verify this class against the real captured-XML equivalence harness (see the
Saxon-HE engine-swap entries above) after any Saxon jar bump.

**Client-side unwrap: `skin-standard-xml/$files/input-label-block-visibility.js`.** Since
`show.xsl.tpl` is off-limits and there's no wrapper element around the `rawHeadData` value-of (it's
a bare disable-output-escaping text/CDATA child of `<head>`, sibling to `<head>`'s other,
all-element children), the unwrap script can't be targeted in by id/class or added as a new
`<script src>` in the template. Two things make this work without either: (1) a direct
`CDATA_SECTION_NODE` (`nodeType === 4`) child of `<head>` is an unambiguous marker — nothing else
this template emits into `<head>` is ever a CDATA section, so no id/class is needed; (2) this file
is one of the few `skin-standard-xml` assets already guaranteed loaded on every page the skin
renders (via `show.xsl.tpl`'s own `initAll()`/`require.script()` bootstrap), so the new code can
live in an existing file instead of a new one. `initRawHeadDataUnwrap()` walks `document.head`'s
children for CDATA sections, concatenates their `.data` in document order (handles the rare
multi-section case `emitAsCdata`'s own `"]]>"`-splitting produces), and hands the result to
`rawHeadDataExtractBlocks` — a hand-rolled scanner, deliberately not `DOMParser`/`innerHTML`-based:
this document may be XML (`application/xhtml+xml`), where `innerHTML`'s fragment-parsing algorithm
enforces XML well-formedness and would choke on a bare `<` inside the inline script's own text (the
real `sDom` value contains exactly that: `'<"tbar-up"fripl<"ui-clear">>t'`). Scanning for the
literal closing-tag token only, ignoring any other `<` in between, matches HTML's own raw-text
parsing rule for `<script>`/`<style>` and sidesteps the well-formedness trap entirely.
`rawHeadDataActivateBlock` then builds each real element via `document.createElement` +
explicit `setAttribute`/`textContent` (never `innerHTML`, which never executes a `<script>` it
inserts), setting `async = false` on a `<script src>` so dynamically-inserted library scripts keep
their document-order execution (jQuery before `jquery.dataTables.min.js`) instead of racing.

**Verified 2026-08-31, real evidence, not just source-reading:**
- `RenderDoeRawHeadShare.js` (`unit-test/magic-tester/testpages/`) was corrected to a byte-faithful
  reproduction of `prepareHtmlTable()`'s real four-block output (previously only ever wrapped one
  bare `<script>`, which is why the old gap wasn't caught — see git history/this entry's prior
  revision for that shape).
- Compiled the fix (`javac` into this project's own `bin/`, picked up ahead of the axiom's packaged
  jar per `verify-ae3-web-dispatch.sh`'s own classpath assembly) and ran it against a real, isolated,
  loopback-only local AE3 server (`unit-test/magic-tester/verify-ae3-web-dispatch.sh start`), then
  fetched `Host: ae3-test-doe-rawhead.local` with `Accept: application/xhtml+xml` — a real `200`,
  `Content-Type: application/xhtml+xml`, `show.xsl.tpl` used completely unmodified.
- `xmllint --noout` on the real response body: well-formed. Python's `xml.dom.minidom` (a real XML
  DOM parser, same CDATA-node semantics any browser's XML parser is spec-required to produce) on the
  same body: exactly one `CDATA_SECTION_NODE` child of `<head>`, its concatenated `.data`
  byte-identical to the original four-block source payload — the whole blob round-trips through
  Saxon's server-side serializer with zero corruption.
- Regression-checked `render-ok`/`render-showfail` hosts (`probe-testpages`, all five Accept-header
  cases): unchanged `200`s, still well-formed `application/xhtml+xml` bodies — this fix's
  unconditional CDATA-wrapping didn't visibly disturb any other `disable-output-escaping` content
  path.
- Ran the real, unmodified `rawHeadDataExtractBlocks`/`rawHeadDataParseAttrs`/
  `rawHeadDataActivateBlock`/`initRawHeadDataUnwrap` functions (loaded verbatim from
  `input-label-block-visibility.js`) through a real JavaScript engine (`jsc`, JavaScriptCore) against
  that exact real extracted CDATA string, with a minimal DOM shim for `document.head`/
  `createElement`/`appendChild`: all four blocks correctly identified in order (script, script,
  script, style), correct `src` attributes, the tricky raw `<"tbar-up"` `sDom` text preserved
  unmangled in the reconstructed inline script's `textContent`, both `<script src>` elements built
  with `async = false`. No real browser was available in the sandbox this fix was built in (no
  `node`/`puppeteer`/Chrome) — this is the strongest evidence achievable there; a real-browser
  spot-check (libraries actually loading, `dataTable()` actually initializing, the `<style>` actually
  applying visually) is still worth doing before/at release.

**`WebContextXml.getResultReply()` now recognizes the `{layout:"final", type:<content-type>}` reply-object sentinel generically, not only a hardcoded `"text/xml"` match.** This sentinel is a generic, pre-existing, cross-cutting AE3 convention — "already fully rendered, serve `content` raw with `type` as the HTTP Content-Type" — produced symmetrically by multiple standard-skin `LayoutDefinition`s (`ae3.sdk`'s `resources/skin/skin-standard/layouts/Xml.jslt` and sibling `Html.jslt`, at minimum), not something specific to XML output; see `FormatSAPI.java`'s javadoc (~line 1570) for the contract. `getResultReply()` now branches on any non-empty `type`. The `X-Debug-Origin: LAYOUT_FINAL` cases referenced earlier in this file (the welcome page, `preview.ndss.knt9.xyz`) are instances of this same generic sentinel, not an XML-only mechanism. General pattern, cross-referenced from `keeper-ae3.armed.md`'s own Domain knowledge.

**PLANNED FIX, approved but not yet implemented (2026-09-01) — revert `show.xsl.tpl` line 9's `<xsl:output>`, paired with explicit Saxon `Serializer` configuration in `XslServerRender.java`. Scoped to this checkout (`/Volumes/workspace/myx/ae3.sys.pkg.l2.tgt.xml/`) only — a separate `/Volumes/ws-2017/` checkout of this same repo is not the target.** Commit `8687e307` ("* XHTML mode") changed line 9 from `<xsl:output method="html" indent="no"/>` to `<xsl:output method="xhtml" indent="no" omit-xml-declaration="yes"/>` to fix the Saxon path's void-element self-closing behavior — but this declaration is also read directly by the old client-side-PI path (`WebContextXml`'s raw reply, browser-side XSLT), which never goes through Saxon. Flipping it to `xhtml` silently switched that client-side path's result parsing from lenient HTML to strict XML, turning a previously-harmless raw `<"` in DataTables' `rawHeadData`/`sDom` config into a real XML parse error in production (`https://ae3.myx.nz/monitoring/runtimeStatsLog?...&___output=xml`, confirmed live: "StartTag: invalid element name"). Approved fix, both parts required together:
1. Revert line 9 to `<xsl:output method="html" indent="no"/>`, its pre-`8687e307` state — fixes the client-side path.
2. Set `Property.METHOD = "xhtml"` and `Property.OMIT_XML_DECLARATION = "yes"` explicitly on `XslServerRender`'s Saxon `Serializer` in Java, so the server-side path stops depending on the stylesheet's own `<xsl:output>` — the revert then can't re-break it, since Java-side serializer properties take precedence over the stylesheet's own `xsl:output`.

**Flagged conflict, not resolved here**: this file's own entries above, and `CLAUDE.md`'s `## Gotchas` section, document `show.xsl.tpl` as owned by `keeper-acm` and permanently off-limits to edit — including a recorded human-owner decision to keep it off-limits. The approved fix above edits this same file's line 9. Recorded as-is, per the approving instruction; not resolved in either direction here.

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
