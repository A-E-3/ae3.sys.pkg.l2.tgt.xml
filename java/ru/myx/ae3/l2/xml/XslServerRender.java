package ru.myx.ae3.l2.xml;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.Configuration;
import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.event.ProxyReceiver;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.event.ReceiverOptions;
import net.sf.saxon.expr.parser.Location;
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
import net.sf.saxon.type.SimpleType;
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

	/** compiled Templates for every "*.xsl.tpl" resource in skin-standard-xml, keyed by public
	 * file name (".tpl" stripped, e.g. "show.xsl.tpl" -> "show.xsl") - matching what result.xsl
	 * actually ends with. Scanned from /union (not just /public) so an override in a
	 * higher-priority VFS tier is picked up.
	 *
	 * Compiled with Saxon-HE ({@link SupplierVfsFolderXslTemplatesCachedSaxon}), not the JDK's
	 * bundled XSLTC - show.xsl.tpl uses a union-of-filter-expressions XPath idiom XSLTC's static
	 * type-checker cannot compile; real browsers already handle the identical, unmodified
	 * construct fine. See that class's own doc comment for the full reasoning. */
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

	/** {@code show.xsl.tpl} (like every other {@code *.xsl.tpl} this pipeline compiles) uses
	 * {@code disable-output-escaping="yes"} in places (e.g. the DataTables {@code sDom} init
	 * string reaching it via {@code rawHeadData}) - legitimate there because the only rendering
	 * path that content has ever run through before now is a real browser's/libxslt's own
	 * client-side XSLT engine, which tolerates it. Saxon's own server-side XML serializer instead
	 * takes {@code disable-output-escaping} completely literally: the raw, unescaped characters
	 * land in the output bytes verbatim, which is not well-formed XML whenever that raw text
	 * itself contains {@code <}/{@code &} (e.g. {@code <"tbar-up"...}), breaking every
	 * {@code application/xhtml+xml} reply that touches such content through this Java path.
	 *
	 * Fixing this without touching show.xsl.tpl (off limits, works fine for its only other,
	 * client-side, consumer) means intercepting the events between the XSLT transform and
	 * Saxon's final serializer, not the stylesheet instruction itself - same idea as the
	 * character-map U+00A0 fix just above, but character maps are documented to skip any
	 * text/attribute event already marked {@code disable-output-escaping} (confirmed by reading
	 * {@code net.sf.saxon.serialize.CharacterMapExpander}'s own {@code characters()}/
	 * {@code attribute()} source: it deliberately passes such events straight through
	 * unmodified), so a character map alone cannot reach this content.
	 *
	 * {@link DisableEscapingNeutralizingReceiver} instead clears Saxon's internal
	 * {@link ReceiverOptions#DISABLE_ESCAPING} bit off every {@code characters()}/
	 * {@code attribute()} event before it reaches Saxon's own {@code XMLEmitter} - which, once
	 * that bit is clear, applies its completely ordinary well-formed-XML escaping to the content,
	 * exactly as it already does for every other text/attribute event (confirmed by reading
	 * {@code net.sf.saxon.serialize.XMLEmitter#characters}: the {@code DISABLE_ESCAPING} bit is
	 * the only thing that ever routes it to the raw/unescaped branch). This is deliberately
	 * unconditional - it neutralizes the instruction for any content that reaches this Java
	 * rendering path, for every stylesheet {@link SupplierVfsFolderXslTemplatesCachedSaxon}
	 * compiles, not specifically the DataTables string that surfaced the bug - matching the same
	 * "general, not one page" shape as the U+00A0 character-map fix above. */
	private static final class DisableEscapingNeutralizingReceiver extends ProxyReceiver {

		DisableEscapingNeutralizingReceiver(final Receiver next) {

			super(next);
		}

		@Override
		public void characters(final CharSequence chars, final Location locationId, final int properties) throws XPathException {

			super.characters(chars, locationId, properties & ~ReceiverOptions.DISABLE_ESCAPING);
		}

		@Override
		public void attribute(final NodeName nameCode, final SimpleType typeCode, final CharSequence value, final Location locationId,
				final int properties) throws XPathException {

			super.attribute(nameCode, typeCode, value, locationId, properties & ~ReceiverOptions.DISABLE_ESCAPING);
		}
	}

	/** s9api {@link Serializer} subclass that inserts {@link DisableEscapingNeutralizingReceiver}
	 * at the head of Saxon's own serialization receiver chain, otherwise built and configured
	 * exactly as stock {@link Serializer} already does (both {@code getReceiver} overloads are
	 * covered since {@link XsltTransformer}'s own {@code destination instanceof Serializer} branch
	 * - confirmed by reading its source - calls the {@link PipelineConfiguration} overload, not
	 * the {@link Configuration} one). Subclassing (rather than wrapping {@link Serializer} behind
	 * a hand-written {@code Destination}) is deliberate: {@code XsltTransformer.setDestination}
	 * special-cases an actual {@code instanceof Serializer}, applying show.xsl.tpl's own
	 * {@code xsl:output} declaration and merging in the U+00A0 character map above - behavior this
	 * class must keep exactly as-is, unrelated to the fix here. Only which {@link Receiver} the
	 * transform ultimately writes events into changes; every other {@link Serializer} setting
	 * (output writer, character map, output properties) set on an instance of this subclass in
	 * {@link #transform(String, String)} works identically to a stock {@link Serializer}. */
	private static final class SerializerXhtmlDisableEscapingNeutralizing extends Serializer {

		SerializerXhtmlDisableEscapingNeutralizing(final Processor processor) {

			super(processor);
		}

		@Override
		public Receiver getReceiver(final Configuration config) throws SaxonApiException {

			return new DisableEscapingNeutralizingReceiver(super.getReceiver(config));
		}

		@Override
		public Receiver getReceiver(final PipelineConfiguration pipe) throws SaxonApiException {

			return new DisableEscapingNeutralizingReceiver(super.getReceiver(pipe));
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
				// same diagnosability gap as the catch below - a lookup miss (bad key, folder
				// scan came back empty, stylesheet not compiled) is otherwise indistinguishable
				// from a successful-but-empty render once it silently falls back to plain xml
				Report.debug("XML-XSL", "No compiled stylesheet found for key '" + key + "' (from xsl='" + xsl + "')");
				throw new RenderException("No compiled stylesheet found for key '" + key + "' (from xsl='" + xsl + "')");
			}
			final StringWriter writer = new StringWriter();
			final Serializer serializer = new SerializerXhtmlDisableEscapingNeutralizing(((XsltExecutable) executable).getProcessor());
			serializer.setOutputWriter(writer);
			serializer.setCharacterMap(XslServerRender.nbspCharacterMapIndex);
			serializer.setOutputProperty(Serializer.Property.USE_CHARACTER_MAPS, XslServerRender.nbspCharacterMapName.getLocalPart());
			final XsltTransformer transformer = ((XsltExecutable) executable).load();
			transformer.setSource(new StreamSource(new StringReader(xml)));
			transformer.setDestination(serializer);
			transformer.transform();
			/** show.xsl.tpl's own xsl:output declares no doctype-system, so this server-rendered
			 * output has none either - unlike every other reply this framework has ever served,
			 * which always resolved to a real "html"/"htm" HtmlDomTargetContext reply carrying its
			 * own doctype further up the chain. A doctype-less document, if a client's content
			 * negotiation ever lands it on the text/html parser instead of the XML one, renders in
			 * quirks mode - a rendering mode real production traffic has never actually been in.
			 * Prepending the minimal HTML5 doctype here (valid, subset-free, and just as legal
			 * preceding an XHTML document as an HTML one) keeps every reply through this path in
			 * standards mode unconditionally, without touching show.xsl.tpl itself. */
			return "<!DOCTYPE html>" + writer.toString();
		} catch (final RenderException e) {
			throw e;
		} catch (final Exception e) {
			/** Was a bare "return null" - every failure (missing/uncompiled stylesheet, malformed
			 * xml, transform error) silently fell back to WebContextXml's plain text/xml reply
			 * with zero trace of why, which is exactly what made the "?___output=xhtml silently
			 * returns xml" symptom impossible to root-cause from outside a debugger/live
			 * instance. Report it (same "log" idiom used elsewhere for a non-fatal skin/render
			 * failure, e.g. SkinImpl's "SKIN-LOADER") and now also throw instead of swallowing it
			 * outright. */
			Report.exception("XML-XSL", "Server-side XSLT transform failed for '" + xsl + "'", e);
			throw new RenderException("Server-side XSLT transform failed for '" + xsl + "'", e);
		}
	}

	/** Builds a {@code <message layout="message" code="..." title="...">} XML document in the same
	 * shape {@code MakeMessageReplyFn.js}'s {@code makeMessageReply(context, layout)} builds for a
	 * {@code {layout:"message", reason, message, detail, code}} object (root element name, the
	 * {@code layout}/{@code code}/{@code title} attributes, the unconditional {@code <reason>}
	 * child defaulting to "Unclassified message.", the {@code <message debug="x-string"
	 * class="code style--block">}/{@code <detail ...>} children for plain-string content), then
	 * renders it through "show.xsl" - the one local stylesheet whose root template dispatches on
	 * {@code @layout} and has a {@code *[@layout='message']} template - via the same
	 * {@link #transform(String, String)} every other branch here already calls.
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
	 *            already-resolved ReplyAnswer that {@code TargetContextAbstract.step()} stopped
	 *            the layout walk on directly (see that method's
	 *            {@code this.currentObject instanceof ReplyAnswer} branch) - some layout
	 *            definition in the skin/context chain built it directly instead of going through
	 *            the normal "message"/"final" layout resolution
	 * @param ownerId
	 *            {@code Reply.string(...)} event owner id, normally
	 *            {@code this.getClass().getSimpleName()}
	 * @param query
	 *            serving request, forwarded to {@code Reply.string(...)}
	 * @return {@code reply} itself, unchanged, when it is already {@link ReplyAnswer#isFinal()
	 *         final}, binary, a file, or a redirect (3xx) - the exact same gates
	 *         {@code ru.myx.ae3.skinner.SkinnerAbstract#handleReply}/{@code #handleReplyOnce}
	 *         use to decide a reply needs no further rendering, copied here verbatim since this is
	 *         the same decision applied to the skin-standard-xml family instead of
	 *         skin-standard-html; otherwise a new {@code application/xhtml+xml} reply carrying the
	 *         same code, the same attributes ({@code reply.getAttributes()} passed straight into
	 *         {@code Reply.string(...)}) and the same flags ({@code useFlags(reply.getFlags())}) as
	 *         {@code reply}, with a body built from {@code reply}'s own title/body - extracted the
	 *         same way {@code SkinnerAbstract.handleReplyOnce} extracts title/body from a
	 *         textual/empty/exotic-object reply - through
	 *         {@link #renderMessage(int, String, CharSequence, CharSequence)} */
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
					.setAttribute("Content-Type", "application/xhtml+xml")//
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
