package ru.myx.ae3.l2.xml;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.serve.ServeRequest;

/** Auto-detecting variant of WebContextXml, for the DEFAULT (not explicitly ___output=xml)
 * rendering path: chooses between the server-side-rendered application/xhtml+xml reply (when the
 * client's Accept header lists it) and the standard client-side-XSLT/text/xml reply that
 * WebContextXml always produces. Exists because browsers are dropping client-side XSLT support -
 * an explicit ___output=xml request still reaches plain WebContextXml and always gets pure XML,
 * regardless of Accept. See WebContextXmlXhtml for the unconditional (Accept-ignoring) variant.
 *
 * @author myx */
public class WebContextXmlAutoDetect extends WebContextXml {

	/** @param target
	 * @param query */
	public WebContextXmlAutoDetect(final TargetInterface target, final ServeRequest query) {

		super(target, query);
	}

	@Override
	public ReplyAnswer getResultReply() {

		final BaseObject resultLayout = this.getResultLayout();
		if (!(resultLayout instanceof ReplyAnswer) && "xml".equals(Base.getString(resultLayout, "layout", "").trim())) {
			final String xsl = Base.getString(resultLayout, "xsl", "").trim();
			if (xsl.length() > 0 && XslServerRender.acceptsXhtml(this.getQuery())) {
				final String xhtml = XslServerRender.transform(xsl, Base.getString(resultLayout, "content", "<none/>"));
				if (xhtml != null) {
					return Reply.string(
							this.getClass().getSimpleName(), //
							this.getQuery(), //
							xhtml) //
							.setCode(Base.getInt(resultLayout, "code", 200))//
							.setAttribute("Content-Type", "application/xhtml+xml")//
							.setFinal();
				}
			}
		}
		return super.getResultReply();
	}
}
