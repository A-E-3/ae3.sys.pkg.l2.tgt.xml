package ru.myx.ae3.l2.xml;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.Configuration;
import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.event.ProxyReceiver;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.expr.parser.Location;
import net.sf.saxon.lib.NamespaceConstant;
import net.sf.saxon.om.NamespaceBinding;
import net.sf.saxon.om.NamespaceBindingSet;
import net.sf.saxon.om.NoNamespaceName;
import net.sf.saxon.om.NodeName;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import net.sf.saxon.serialize.CharacterMap;
import net.sf.saxon.serialize.CharacterMapIndex;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.SchemaType;
import net.sf.saxon.z.IntHashMap;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.base.BaseString;
import ru.myx.ae3.common.BodyAccessCharacter;
import ru.myx.ae3.report.Report;
import ru.myx.ae3.serve.ServeRequest;
import ru.myx.ae3.vfs.Storage;

/** Shared server-side XSLT rendering for skin-standard-xml, used by both
 * {@link WebContextXmlAutoDetect} (Accept-gated) and {@link WebContextXmlXhtml} (unconditional).
 *
 * @author myx */
final class XslServerRender {

	/** Compiled {@code *.xsl.tpl} templates for skin-standard-xml, keyed by public file name
	 * (".tpl" stripped, e.g. "show.xsl.tpl" -> "show.xsl"). Scanned from /union so a
	 * higher-priority VFS tier override is picked up. Compiled with Saxon-HE, not XSLTC — see
	 * {@link SupplierVfsFolderXslTemplatesCachedSaxon}'s own doc comment. */
	private static final SupplierVfsFolderXslTemplatesCachedSaxon xslTemplates = new SupplierVfsFolderXslTemplatesCachedSaxon(//
			Storage.UNION.relative("resources/skin/skin-standard-xml", null));

	/** U+00A0 mapped to its legal XML numeric character reference; covers attribute values too,
	 * unlike the SerializerFactoryHtmlNbspFix Emitter subclass it replaces. */
	private static final CharacterMapIndex nbspCharacterMapIndex;

	private static final StructuredQName nbspCharacterMapName = new StructuredQName("", "", "nbsp-fix");

	static {
		final IntHashMap<String> map = new IntHashMap<>(4);
		map.put(160, "&#160;");
		final CharacterMap characterMap = new CharacterMap(XslServerRender.nbspCharacterMapName, map);
		nbspCharacterMapIndex = new CharacterMapIndex();
		XslServerRender.nbspCharacterMapIndex.putCharacterMap(characterMap.getName(), characterMap);
	}

	/** @param query
	 * @return true when the client's Accept header lists application/xhtml+xml */
	static boolean acceptsXhtml(final ServeRequest query) {

		return Base.getString(query.getAttributes(), "Accept", "").contains("application/xhtml+xml");
	}

	/** Thrown by {@link #transform(String, String)} to carry the real failure reason to the
	 * caller instead of the historic silent "return null" fallback.
	 *
	 * @author myx */
	static final class RenderException extends Exception {

		RenderException(final String message) {

			super(message);
		}

		RenderException(final String message, final Throwable cause) {

			super(message, cause);
		}
	}

	/** Saxon-HE's {@code net.sf.saxon.serialize.HTMLEmitter} decides whether an element is an HTML
	 * element by testing for the empty namespace URI, and it does so in two separate places: whether
	 * {@code <script>}/{@code <style>} content is raw CDATA text ({@code HTMLEmitter#startElement},
	 * {@code elemName.hasURI("")}) and whether a void element needs no end tag
	 * ({@code HTML40Emitter#isHTMLElement}, {@code name.getURI().equals("")}). show.xsl.tpl's root
	 * output element declares {@code xmlns="http://www.w3.org/1999/xhtml"}, so every element it emits
	 * carries the XHTML namespace and both decisions come out wrong at once: script bodies get
	 * ordinary text escaping, and a browser reads {@code &amp;&amp;} as literal text instead of
	 * {@code &&}; void elements get spurious end tags, and {@code </br>} is read as a second
	 * {@code <br>}.
	 *
	 * Normalizing that namespace away before the emitter sees it fixes both through stock Saxon,
	 * instead of re-implementing each decision in an emitter subclass one at a time - which is
	 * open-ended, since every HTML-awareness decision in that emitter is gated the same way. Only
	 * {@code http://www.w3.org/1999/xhtml} is stripped: any other namespace passes through untouched,
	 * so a foreign vocabulary (SVG, MathML) reaching this path keeps the namespace its own
	 * serialization rules depend on. Attribute names are left alone - an unprefixed attribute is
	 * already in no namespace, and neither emitter decision looks at attributes. */
	private static final class XhtmlNamespaceStrippingReceiver extends ProxyReceiver {

		XhtmlNamespaceStrippingReceiver(final Receiver next) {

			super(next);
		}

		@Override
		public void startElement(final NodeName elemName, final SchemaType typeCode, final Location location, final int properties) throws XPathException {

			super.startElement(elemName.hasURI(NamespaceConstant.XHTML)
				? new NoNamespaceName(elemName.getLocalPart())
				: elemName, typeCode, location, properties);
		}

		@Override
		public void namespace(final NamespaceBindingSet namespaceBindings, final int properties) throws XPathException {

			for (final NamespaceBinding binding : namespaceBindings) {
				/** a NamespaceBinding is itself a single-entry NamespaceBindingSet, so a surviving one forwards as-is */
				if (!NamespaceConstant.XHTML.equals(binding.getURI())) {
					super.namespace(binding, properties);
				}
			}
		}
	}

	/** s9api {@link Serializer} subclass inserting {@link XhtmlNamespaceStrippingReceiver} at the head
	 * of Saxon's own serialization receiver chain, otherwise built and configured exactly as a stock
	 * {@link Serializer}. Subclassing, rather than wrapping one behind a hand-written
	 * {@code Destination}, is load-bearing: {@code XsltTransformer.setDestination} special-cases an
	 * actual {@code instanceof Serializer}, and that branch is what applies show.xsl.tpl's own
	 * {@code xsl:output} declaration and merges in the U+00A0 character map above - a plain
	 * {@code Destination} wrapper silently loses both. Both {@code getReceiver} overloads are covered
	 * because that same branch calls the {@link PipelineConfiguration} one. */
	private static final class SerializerHtmlXhtmlNamespaceStrip extends Serializer {

		SerializerHtmlXhtmlNamespaceStrip(final Processor processor) {

			super(processor);
		}

		@Override
		public Receiver getReceiver(final Configuration config) throws SaxonApiException {

			return new XhtmlNamespaceStrippingReceiver(super.getReceiver(config));
		}

		@Override
		public Receiver getReceiver(final PipelineConfiguration pipe) throws SaxonApiException {

			return new XhtmlNamespaceStrippingReceiver(super.getReceiver(pipe));
		}
	}

	/** @param xsl
	 *            result.xsl value, used to look up a scanned stylesheet by file name
	 * @param xml
	 * @return transformed xhtml
	 * @throws RenderException
	 *             when xsl doesn't name a known stylesheet, the stylesheet isn't compiled yet, or
	 *             the transform failed - caller decides how to react (was: silently returned
	 *             null and fell back to the plain client-PI/text/xml reply) */
	static String transform(final String xsl, final String xml) throws RenderException {

		try {
			final String key = XslServerRender.fileName(xsl);
			final Object executable = XslServerRender.xslTemplates.get()//
					.baseGet(key, BaseObject.UNDEFINED)//
					.baseValue();
			if (!(executable instanceof XsltExecutable)) {
				// same diagnosability gap as the catch below - must not silently fall back to plain xml
				Report.debug("XML-XSL", "No compiled stylesheet found for key '" + key + "' (from xsl='" + xsl + "')");
				throw new RenderException("No compiled stylesheet found for key '" + key + "' (from xsl='" + xsl + "')");
			}
			final StringWriter writer = new StringWriter();
			// disable-output-escaping content (rawHeadData's DataTables blob) is now served as
			// text/html, where a real <script>/<style> block is exactly what's wanted - no
			// neutralizing/CDATA-wrapping receiver needed; the stock Serializer's own XMLEmitter
			// already emits DISABLE_ESCAPING content raw/literal, which is the correct behavior here.
			final Serializer serializer = new SerializerHtmlXhtmlNamespaceStrip(((XsltExecutable) executable).getProcessor());
			serializer.setOutputWriter(writer);
			serializer.setCharacterMap(XslServerRender.nbspCharacterMapIndex);
			serializer.setOutputProperty(Serializer.Property.USE_CHARACTER_MAPS, XslServerRender.nbspCharacterMapName.getLocalPart());
			// "html", not "xhtml": replies here are served text/html, where script/style content must stay unescaped
			serializer.setOutputProperty(Serializer.Property.METHOD, "html");
			serializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
			final XsltTransformer transformer = ((XsltExecutable) executable).load();
			transformer.setSource(new StreamSource(new StringReader(xml)));
			transformer.setDestination(serializer);
			transformer.transform();
			return "<!DOCTYPE html>" + writer.toString();
		} catch (final RenderException e) {
			throw e;
		} catch (final Exception e) {
			// was a silent "return null"; now reports (XML-XSL) and throws instead - see MAGIC.md
			Report.exception("XML-XSL", "Server-side XSLT transform failed for '" + xsl + "'", e);
			throw new RenderException("Server-side XSLT transform failed for '" + xsl + "'", e);
		}
	}

	/** Builds a {@code <message layout="message">} XML document in the same shape
	 * {@code MakeMessageReplyFn.js}'s {@code makeMessageReply} builds, then renders it through
	 * "show.xsl". Full shape mapping: this package's {@code MAGIC.md}.
	 *
	 * @param code
	 *            reply code, becomes both the {@code code} attribute and the "Code: NNN" line
	 * @param reason
	 *            short title/reason line; falls back to "Unclassified message." when null/blank
	 * @param message
	 *            longer message body, omitted entirely when null/empty
	 * @param detail
	 *            collapsible detail block (e.g. a stack trace), omitted entirely when null/empty
	 * @return rendered XHTML
	 * @throws RenderException
	 *             when show.xsl itself isn't compiled/reachable or the transform fails */
	static String renderMessage(final int code, final String reason, final CharSequence message, final CharSequence detail) throws RenderException {

		final String title = reason == null || reason.trim().length() == 0
			? "Unclassified message."
			: reason.trim();
		final StringBuilder xml = new StringBuilder(256);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		xml.append("<message layout=\"message\" code=\"").append(code).append("\" title=\"").append(XslServerRender.escapeXml(title)).append("\">");
		xml.append("<reason>").append(XslServerRender.escapeXml(title)).append("</reason>");
		if (message != null && message.length() > 0) {
			xml.append("<message debug=\"x-string\" class=\"code style--block\">")//
					.append(XslServerRender.escapeXml(message.toString()))//
					.append("</message>");
		}
		if (detail != null && detail.length() > 0) {
			xml.append("<detail debug=\"x-string\" class=\"code style--block\">")//
					.append(XslServerRender.escapeXml(detail.toString()))//
					.append("</detail>");
		}
		xml.append("</message>");
		return XslServerRender.transform("show.xsl", xml.toString());
	}

	/** @param reply
	 *            already-resolved ReplyAnswer from a layout definition that built it directly (see
	 *            {@code TargetContextAbstract.step()}'s
	 *            {@code this.currentObject instanceof ReplyAnswer} branch)
	 * @param ownerId
	 *            {@code Reply.string(...)} event owner id
	 * @param query
	 *            serving request, forwarded to {@code Reply.string(...)}
	 * @return {@code reply} unchanged when already final/binary/file/redirect; otherwise a new
	 *         {@code application/xhtml+xml} reply rendered via
	 *         {@link #renderMessage(int, String, CharSequence, CharSequence)}. Full gating and
	 *         copying rationale: this package's {@code MAGIC.md}. */
	static ReplyAnswer renderReplyIfNeeded(final ReplyAnswer reply, final String ownerId, final ServeRequest query) {

		if (reply.isFinal() || reply.isBinary() || reply.isFile() || reply.getCode() / 100 == 3) {
			return reply;
		}
		final CharSequence body;
		if (reply.isCharacter()) {
			body = ((BodyAccessCharacter) reply).getText();
		} else if (reply.isEmpty()) {
			body = BaseString.EMPTY;
		} else {
			CharSequence text;
			try {
				text = reply.toCharacter().getText();
			} catch (final Throwable e) {
				text = e.toString();
			}
			body = text;
		}
		try {
			final String xhtml = XslServerRender.renderMessage(reply.getCode(), reply.getTitle(), body, null);
			return Reply.string(ownerId, query, reply.getAttributes(), xhtml)//
					.setCode(reply.getCode())//
					.setAttribute("Content-Type", "text/html")//
					.setFinal()//
					.useFlags(reply.getFlags());
		} catch (final RenderException e) {
			Report.debug("XML-XSL", "Could not re-render pre-built ReplyAnswer through show.xsl: " + e.getMessage());
			return reply;
		}
	}

	/** Minimal XML-escaping matching {@code Format.xmlAttributes}/{@code Format.xmlNodeValue}'s
	 * {@code & < > " '} coverage (see {@code MakeMessageReplyFn.js}) - safe for both the attribute
	 * values and the text node content built above.
	 *
	 * @param value
	 * @return */
	private static String escapeXml(final String value) {

		return value//
				.replace("&", "&amp;")//
				.replace("<", "&lt;")//
				.replace(">", "&gt;")//
				.replace("\"", "&quot;")//
				.replace("'", "&#39;");
	}

	/** @param path
	 * @return the trailing file name segment of path, lower-cased (path itself when there's no
	 *         '/') */
	private static String fileName(final String path) {

		final int slash = path.lastIndexOf('/');
		return (slash < 0
			? path
			: path.substring(slash + 1)).toLowerCase();
	}

	private XslServerRender() {

		// static utility, not instantiable
	}
}
