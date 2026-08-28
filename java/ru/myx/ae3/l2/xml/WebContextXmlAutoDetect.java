package ru.myx.ae3.l2.xml;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.l2.NativeTargetContext;
import ru.myx.ae3.serve.ServeRequest;

/** Auto-detecting variant of WebContextXml, for the DEFAULT (not explicitly ___output=xml)
 * rendering path: chooses between the server-side-rendered application/xhtml+xml reply (when the
 * client's Accept header lists it) and the standard client-side-XSLT/text/xml reply that
 * WebContextXml always produces. Exists because browsers are dropping client-side XSLT support -
 * an explicit ___output=xml request still reaches plain WebContextXml and always gets pure XML,
 * regardless of Accept. See WebContextXmlXhtml for the unconditional (Accept-ignoring) variant.
 *
 * Uses WebContextXml's package-visible three-argument constructor to get
 * {@link NativeTargetContext.TargetMode#CLONE_SKINNED} instead of the public constructor's
 * historic {@link NativeTargetContext.TargetMode#CLONE} - same reasoning as WebContextXmlXhtml.
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
			// same real-render upgrade WebContextXmlXhtml applies unconditionally, gated here on
			// Accept - matching this class' whole reason for existing (implicit/default path,
			// must still degrade gracefully to the raw reply when the client didn't ask for
			// xhtml at all).
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
							.setAttribute("Content-Type", "application/xhtml+xml")//
							.setFinal();
				} catch (final XslServerRender.RenderException e) {
					// silent fallback to the standard client-side-XSLT/text/xml reply below -
					// this is the implicit/default (no explicit ___output) path, so it must keep
					// degrading gracefully on any transform failure exactly as before, same as
					// when transform() used to return null here. See WebContextXmlXhtml for the
					// explicit ___output=xhtml counterpart, which surfaces this loudly instead.
				}
			}
		}
		return super.getResultReply();
	}
}
