package ru.myx.ae3.l2.xml;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	/** Neutralizes Saxon's {@code DISABLE_ESCAPING} events reaching the server-side serializer, so
	 * {@code show.xsl.tpl}'s {@code disable-output-escaping="yes"} content (safe for its original
	 * client-side XSLT consumer, unsafe for Saxon's own literal server-side serializer) becomes
	 * well-formed XML instead — except a single self-contained {@code <script>}/{@code <style>}
	 * block, CDATA-wrapped so it stays live (see {@link #RAW_TAG_WRAPPER}). Full rationale,
	 * Saxon-internals confirmation, and coupling risk: this package's {@code MAGIC.md}. */
	private static final class DisableEscapingNeutralizingReceiver extends ProxyReceiver {

		/** Matches a {@code characters()} raw value that is, in its entirety, one
		 * {@code <script ...>...</script>} or {@code <style ...>...</style>} block - group 1 is the
		 * opening tag (kept raw), group 3 is the interior (CDATA-wrapped), group 4 is the closing
		 * tag (kept raw). Anchored both ends: only an exact, single wrapper qualifies. */
		private static final Pattern RAW_TAG_WRAPPER = Pattern
				.compile("(?is)\\A\\s*(<(script|style)\\b[^>]*>)(.*)(</\\2\\s*>)\\s*\\z");

		DisableEscapingNeutralizingReceiver(final Receiver next) {

			super(next);
		}

		/** Splits {@code interior} around any literal {@code "]]>"} (illegal inside a CDATA
		 * section) and writes each piece as its own CDATA section into {@code next}, with
		 * {@code DISABLE_ESCAPING} set throughout so {@code XMLEmitter} emits it literally. */
		private void emitAsCdata(final CharSequence interior, final Location locationId, final int properties) throws XPathException {

			final int chprop = properties | ReceiverOptions.DISABLE_ESCAPING | ReceiverOptions.DISABLE_CHARACTER_MAPS;
			final String text = interior.toString();
			final int length = text.length();
			super.characters("<![CDATA[", locationId, chprop);
			int from = 0;
			for (int i = 0; i < length - 2; i++) {
				if (text.charAt(i) == ']' && text.charAt(i + 1) == ']' && text.charAt(i + 2) == '>') {
					super.characters(text.substring(from, i + 2), locationId, chprop);
					super.characters("]]><![CDATA[", locationId, chprop);
					from = i + 2;
					i += 2;
				}
			}
			super.characters(text.substring(from), locationId, chprop);
			super.characters("]]>", locationId, chprop);
		}

		@Override
		public void characters(final CharSequence chars, final Location locationId, final int properties) throws XPathException {

			if ((properties & ReceiverOptions.DISABLE_ESCAPING) != 0) {
				final Matcher matcher = DisableEscapingNeutralizingReceiver.RAW_TAG_WRAPPER.matcher(chars);
				if (matcher.matches()) {
					super.characters(matcher.group(1), locationId, properties);
					this.emitAsCdata(matcher.group(3), locationId, properties);
					super.characters(matcher.group(4), locationId, properties);
					return;
				}
			}
			super.characters(chars, locationId, properties & ~ReceiverOptions.DISABLE_ESCAPING);
		}

		@Override
		public void attribute(final NodeName nameCode, final SimpleType typeCode, final CharSequence value, final Location locationId,
				final int properties) throws XPathException {

			// attribute values can't legally hold CDATA/raw '<' - the exception above is characters()-only
			super.attribute(nameCode, typeCode, value, locationId, properties & ~ReceiverOptions.DISABLE_ESCAPING);
		}
	}

	/** s9api {@link Serializer} subclass that inserts {@link DisableEscapingNeutralizingReceiver}
	 * at the head of Saxon's serialization chain; every other {@link Serializer} setting behaves
	 * exactly as stock. Subclassing (not wrapping) is deliberate — rationale: this package's
	 * {@code MAGIC.md}. */
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
				// same diagnosability gap as the catch below - must not silently fall back to plain xml
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
			// show.xsl.tpl declares no doctype-system; prepend minimal HTML5 doctype to avoid quirks mode - see MAGIC.md
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
