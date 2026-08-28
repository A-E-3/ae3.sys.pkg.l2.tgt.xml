package ru.myx.ae3.l2.xml;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.help.Format;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.l2.NativeTargetContext;
import ru.myx.ae3.serve.ServeRequest;

/** Forced-XHTML variant of WebContextXml: always attempts the server-side XSLT-rendered
 * application/xhtml+xml reply, ignoring the Accept header entirely - the explicit counterpart to
 * plain ___output=xml always being pure XML. Falls back to WebContextXml's standard
 * client-side-XSLT/text/xml reply only when there's no xsl, no matching compiled stylesheet, or
 * the transform fails. See WebContextXmlAutoDetect for the Accept-gated variant.
 *
 * Uses WebContextXml's package-visible three-argument constructor to get
 * {@link NativeTargetContext.TargetMode#CLONE_SKINNED} instead of the public constructor's
 * historic {@link NativeTargetContext.TargetMode#CLONE} - this is the one registration that
 * actually needs the skin-driven walk (so a nested "data-view" layout gets reduced to the
 * "xml"+xsl sentinel this class' getResultReply() below relies on); plain WebContextXml stays raw.
 *
 * @author myx */
public class WebContextXmlXhtml extends WebContextXml {

	/** @param target
	 * @param query */
	public WebContextXmlXhtml(final TargetInterface target, final ServeRequest query) {

		super(target, query, NativeTargetContext.TargetMode.CLONE_SKINNED);
	}

	@Override
	public ReplyAnswer getResultReply() {

		final BaseObject resultLayout = this.getResultLayout();
		if (resultLayout instanceof ReplyAnswer) {
			// some layout definition built a ReplyAnswer directly instead of going through the
			// normal "message"/"final" layout resolution - render it for real (same
			// message-layout -> show.xsl path used below) rather than forwarding it unchanged
			// with whatever content-type it happened to carry.
			return XslServerRender.renderReplyIfNeeded((ReplyAnswer) resultLayout, this.getClass().getSimpleName(), this.getQuery());
		}
		final String resultLayoutName = Base.getString(resultLayout, "layout", "").trim();
		/** "final" replies with type="text/xml" (e.g. CliBridge.js) carry raw XML content the
		 * same way an "xml"-layout reply does, just via `type` instead of an explicit `xsl` -
		 * default to show.xsl, the same stylesheet every "xml"-layout reply already assumes when
		 * it names it explicitly. Any other "final" type (text/plain, text/json, ...) is
		 * genuinely not XML and must keep falling through unchanged below. */
		if ("xml".equals(resultLayoutName)
				|| "final".equals(resultLayoutName) && "text/xml".equals(Base.getString(resultLayout, "type", "").trim())) {
			final String xsl = "xml".equals(resultLayoutName)
					? Base.getString(resultLayout, "xsl", "").trim()
					: "show.xsl";
			if (xsl.length() > 0) {
				try {
					final String xhtml = XslServerRender.transform(xsl, Base.getString(resultLayout, "content", "<none/>"));
					return Reply.string(
							this.getClass().getSimpleName(), //
							this.getQuery(), //
							xhtml) //
							.setCode(Base.getInt(resultLayout, "code", 200))//
							.setAttribute("Content-Type", "application/xhtml+xml")//
							.setFinal();
				} catch (final XslServerRender.RenderException e) {
					// explicit ___output=xhtml was deliberately requested, so surface the
					// failure loudly instead of silently degrading to the client-side-XSLT/
					// text/xml reply (that silent fallback is WebContextXmlAutoDetect's job,
					// for the implicit/default path only). Rendered through the same real
					// message-layout -> show.xsl path Share.js/UiBasic.js's
					// makeServerFailureLayout -> MakeMessageReplyFn.js's makeMessageReply ->
					// show.xsl.tpl's *[@layout='message'] template already uses for a genuine
					// styled AE3 error page - no exception, no throw, built synchronously here.
					try {
						final String recovered = XslServerRender.renderMessage(
								500,
								"Server-side XHTML render failed",
								e.getMessage(),
								Format.Throwable.toText(e));
						return Reply.string(
								this.getClass().getSimpleName(), //
								this.getQuery(), //
								recovered) //
								.setCode(500)//
								.setAttribute("Content-Type", "application/xhtml+xml")//
								.setFinal();
					} catch (final XslServerRender.RenderException e2) {
						// show.xsl itself is unreachable/broken - last-resort hand-rolled HTML,
						// kept independent of the rendering pipeline that just failed twice, so
						// we never fail to answer at all.
						return Reply.string(
								this.getClass().getSimpleName(), //
								this.getQuery(), //
								"<!DOCTYPE html>" //
										+ "<html><head><title>Server-side XHTML render failed</title></head>" //
										+ "<body><h1>Server-side XHTML render failed</h1><p>" //
										+ WebContextXmlXhtml.escapeHtml(e.getMessage()) //
										+ "</p></body></html>") //
								.setCode(500)//
								.setAttribute("Content-Type", "text/html")//
								.setFinal();
					}
				}
			}
		}
		return super.getResultReply();
	}

	/** Minimal HTML-escaping for embedding a render-failure message into the hardcoded error
	 * template above - deliberately not routed through any shared/skin escaping helper, to keep
	 * the error path independent of the rendering pipeline that just failed.
	 *
	 * @param value
	 * @return value with {@code & < > " '} replaced by their HTML entities ("" when value is
	 *         null) */
	private static String escapeHtml(final String value) {

		if (value == null) {
			return "";
		}
		return value//
				.replace("&", "&amp;")//
				.replace("<", "&lt;")//
				.replace(">", "&gt;")//
				.replace("\"", "&quot;")//
				.replace("'", "&#39;");
	}
}
