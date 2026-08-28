package ru.myx.ae3.l2.xml;

import java.util.Properties;

import net.sf.saxon.Configuration;
import net.sf.saxon.event.ReceiverOptions;
import net.sf.saxon.expr.parser.Location;
import net.sf.saxon.lib.SaxonOutputKeys;
import net.sf.saxon.lib.SerializerFactory;
import net.sf.saxon.om.NodeName;
import net.sf.saxon.serialize.Emitter;
import net.sf.saxon.serialize.HTML40Emitter;
import net.sf.saxon.serialize.HTML50Emitter;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.SchemaType;

/** Saxon-HE 9.8's {@code net.sf.saxon.serialize.HTMLEmitter} (base of both {@code HTML40Emitter} and
 * {@code HTML50Emitter}) decides whether text is inside a {@code <script>}/{@code <style>} element -
 * and therefore must be serialized unescaped, per the "html" output method's own CDATA-content-model
 * rule for those two elements - by checking {@code elemName.hasURI("")} directly, a hardcoded,
 * namespace-null-only check baked into {@code HTMLEmitter.startElement()}/{@code characters()}.
 * Confirmed by reading Saxon-HE 9.8.0-15's own shipped sources
 * ({@code ae3.pkg.lib.util.saxon-he/incoming/saxon-he-9.8.0-15-sources.jar}). That check never
 * accounts for the XHTML namespace, unlike the namespace-aware {@code isHTMLElement(NodeName)} the
 * very same base class already uses for empty-tag (void-element) handling (see
 * {@code HTML50Emitter#isHTMLElement}: {@code uri.equals("") || uri.equals(NamespaceConstant.XHTML)}).
 *
 * show.xsl.tpl's root output element declares {@code xmlns="http://www.w3.org/1999/xhtml"}, so every
 * literal {@code <script>}/{@code <style>} element it emits carries that XHTML namespace URI, not the
 * empty one - meaning Saxon's own script/style detection never fires for this stylesheet's script
 * blocks, and their content gets ordinary HTML text escaping instead of the CDATA-style passthrough
 * real browsers' client-side XSLT engines (and libxslt, this codebase's disclosed stand-in for them)
 * already apply. Confirmed by a real equivalence run against captured production XML: Saxon emitted
 * {@code &amp;} where libxslt (and this stylesheet's own 20+ years of real-browser rendering) emit a
 * bare, unescaped {@code &} - show.xsl.tpl:176's {@code '&amp;'} JS string literal is itself just an
 * XML-escaped single '&' character in the .tpl source, meant to reach the browser unescaped.
 *
 * Fixed by hooking Saxon's own documented per-{@link Configuration} extension point -
 * {@code SerializerFactory.newHTMLEmitter(Properties)}'s own javadoc: "This method exists so that it
 * can be overridden in a subclass." - to hand back a small subclass of whichever stock HTML4/HTML5
 * emitter Saxon would otherwise have chosen (same {@code html-version} dispatch logic the stock
 * method itself uses, {@link SaxonOutputKeys#isHtmlVersion5(Properties)}) that re-tracks script/style
 * nesting itself, matching by local element name only (case-insensitively, same as every stock
 * HTMLEmitter already does for the elements it does recognize) - not gated on the element's namespace
 * URI at all, since this codebase's only caller ({@link SupplierVfsFolderXslTemplatesCachedSaxon})
 * exists solely to render skin-standard-xml's own XHTML-namespaced .xsl.tpl stylesheets. The stock
 * emitter's own {@code isHTMLElement()}/empty-tag handling is untouched - this only changes which
 * text gets {@code DISABLE_ESCAPING}.
 *
 * @author myx */
final class SerializerFactoryHtmlScriptStyleFix extends SerializerFactory {

	SerializerFactoryHtmlScriptStyleFix(final Configuration config) {

		super(config);
	}

	@Override
	protected Emitter newHTMLEmitter(final Properties properties) {

		return SaxonOutputKeys.isHtmlVersion5(properties)
			? new Html50EmitterScriptStyleFix()
			: new Html40EmitterScriptStyleFix();
	}

	private static boolean isScriptOrStyle(final NodeName elemName) {

		final String local = elemName.getLocalPart();
		return "script".equalsIgnoreCase(local) || "style".equalsIgnoreCase(local);
	}

	/** Re-tracks {@code <script>}/{@code <style>} nesting depth the same way the stock
	 * {@code HTMLEmitter} does internally (a simple depth counter, incremented on every nested
	 * startElement once triggered, decremented on every matching endElement) - except the trigger is
	 * this file's own namespace-agnostic {@link #isScriptOrStyle(NodeName)} instead of the stock
	 * class's private, namespace-null-only check. This is the {@code html-version="4.0"} (Saxon's own
	 * default, since show.xsl.tpl sets no {@code html-version}/{@code version} output property)
	 * variant. */
	private static final class Html40EmitterScriptStyleFix extends HTML40Emitter {

		private int scriptStyleDepth = 0;

		@Override
		public void startElement(final NodeName elemName, final SchemaType typeCode, final Location location, final int properties) throws XPathException {

			super.startElement(elemName, typeCode, location, properties);
			if (this.scriptStyleDepth > 0 || SerializerFactoryHtmlScriptStyleFix.isScriptOrStyle(elemName)) {
				this.scriptStyleDepth++;
			}
		}

		@Override
		public void endElement() throws XPathException {

			if (this.scriptStyleDepth > 0) {
				this.scriptStyleDepth--;
			}
			super.endElement();
		}

		@Override
		public void characters(final CharSequence chars, final Location locationId, final int properties) throws XPathException {

			super.characters(chars, locationId, this.scriptStyleDepth > 0
				? properties | ReceiverOptions.DISABLE_ESCAPING
				: properties);
		}
	}

	/** Same fix as {@link Html40EmitterScriptStyleFix}, for the {@code html-version="5.0"} case - not
	 * currently reached by show.xsl.tpl, kept consistent with Saxon's own default dispatch so this
	 * fix does not silently regress if that ever changes. */
	private static final class Html50EmitterScriptStyleFix extends HTML50Emitter {

		private int scriptStyleDepth = 0;

		@Override
		public void startElement(final NodeName elemName, final SchemaType typeCode, final Location location, final int properties) throws XPathException {

			super.startElement(elemName, typeCode, location, properties);
			if (this.scriptStyleDepth > 0 || SerializerFactoryHtmlScriptStyleFix.isScriptOrStyle(elemName)) {
				this.scriptStyleDepth++;
			}
		}

		@Override
		public void endElement() throws XPathException {

			if (this.scriptStyleDepth > 0) {
				this.scriptStyleDepth--;
			}
			super.endElement();
		}

		@Override
		public void characters(final CharSequence chars, final Location locationId, final int properties) throws XPathException {

			super.characters(chars, locationId, this.scriptStyleDepth > 0
				? properties | ReceiverOptions.DISABLE_ESCAPING
				: properties);
		}
	}
}
