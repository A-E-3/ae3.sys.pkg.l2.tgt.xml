package ru.myx.ae3.l2.xml;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.l2.NativeTargetContext;
import ru.myx.ae3.serve.ServeRequest;

/** Auto-detecting variant of WebContextXml for the default (not explicit ___output=xml) path:
 * server-side-rendered application/xhtml+xml when Accept lists it, else the standard
 * client-side-XSLT/text/xml reply. Full rationale: this package's {@code MAGIC.md}; see
 * {@link WebContextXmlXhtml} for the unconditional variant.
 *
 * @author myx */
public class WebContextXmlAutoDetect extends WebContextXml {

	/** @param target
	 * @param query */
	public WebContextXmlAutoDetect(final TargetInterface target, final ServeRequest query) {

		super(target, query, NativeTargetContext.TargetMode.CLONE_SKINNED);
	}

	@Override
	public ReplyAnswer getResultReply() {

		final BaseObject resultLayout = this.getResultLayout();
		if (resultLayout instanceof ReplyAnswer) {
			// same real-render upgrade WebContextXmlXhtml applies unconditionally, gated here on Accept
			return XslServerRender.acceptsXhtml(this.getQuery())
				? XslServerRender.renderReplyIfNeeded((ReplyAnswer) resultLayout, this.getClass().getSimpleName(), this.getQuery())
				: super.getResultReply();
		}
		if ("xml".equals(Base.getString(resultLayout, "layout", "").trim())) {
			final String xsl = Base.getString(resultLayout, "xsl", "").trim();
			if (xsl.length() > 0 && XslServerRender.acceptsXhtml(this.getQuery())) {
				try {
					final String xhtml = XslServerRender.transform(xsl, Base.getString(resultLayout, "content", "<none/>"));
					return Reply.string(
							this.getClass().getSimpleName(), //
							this.getQuery(), //
							xhtml) //
							.setCode(Base.getInt(resultLayout, "code", 200))//
							.setAttribute("Content-Type", "text/html")//
							.setFinal();
				} catch (final XslServerRender.RenderException e) {
					// intentional silent fallback to the raw reply below - default path must degrade gracefully
				}
			}
		}
		return super.getResultReply();
	}
}
