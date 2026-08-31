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
 * application/xhtml+xml reply, ignoring Accept - the explicit counterpart to plain
 * ___output=xml always being pure XML. Falls back to WebContextXml's standard reply only when
 * there's no xsl, no matching compiled stylesheet, or the transform fails. Full rationale: this
 * package's {@code MAGIC.md}; see {@link WebContextXmlAutoDetect} for the Accept-gated variant.
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
			// a layout definition built this ReplyAnswer directly - render it for real, don't forward unchanged
			return XslServerRender.renderReplyIfNeeded((ReplyAnswer) resultLayout, this.getClass().getSimpleName(), this.getQuery());
		}
		final String resultLayoutName = Base.getString(resultLayout, "layout", "").trim();
		// "final" + type="text/xml" (e.g. CliBridge.js) carries raw XML like "xml"-layout does, via `type` not `xsl` - defaults to show.xsl
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
					// explicit ___output=xhtml was requested - surface the failure loudly (styled AE3 error page), not a silent fallback; see MAGIC.md
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
						// show.xsl itself unreachable/broken - last-resort hand-rolled HTML, independent of the failed pipeline
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

	/** Minimal HTML-escaping for the hardcoded error template above - deliberately not routed
	 * through any shared/skin escaping helper, to stay independent of the failed pipeline.
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
