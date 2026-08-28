package ru.myx.ae3.l2.xml;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.transform.Templates;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

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
			final Object templates = XslServerRender.xslTemplates.get()//
					.baseGet(key, BaseObject.UNDEFINED)//
					.baseValue();
			if (!(templates instanceof Templates)) {
				// same diagnosability gap as the catch below - a lookup miss (bad key, folder
				// scan came back empty, stylesheet not compiled) is otherwise indistinguishable
				// from a successful-but-empty render once it silently falls back to plain xml
				Report.debug("XML-XSL", "No compiled stylesheet found for key '" + key + "' (from xsl='" + xsl + "')");
				throw new RenderException("No compiled stylesheet found for key '" + key + "' (from xsl='" + xsl + "')");
			}
			final StringWriter writer = new StringWriter();
			((Templates) templates).newTransformer().transform(//
					new StreamSource(new StringReader(xml)), //
					new StreamResult(writer));
			return writer.toString();
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
