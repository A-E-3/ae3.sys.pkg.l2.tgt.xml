package ru.myx.ae3.l2.xml;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.i3.web.WebContextXml;
import ru.myx.ae3.serve.ServeRequest;

/** Forced-XHTML variant of WebContextXml: always attempts the server-side XSLT-rendered
 * application/xhtml+xml reply, ignoring the Accept header entirely - the explicit counterpart to
 * plain ___output=xml always being pure XML. Falls back to WebContextXml's standard
 * client-side-XSLT/text/xml reply only when there's no xsl, no matching compiled stylesheet, or
 * the transform fails. See WebContextXmlAutoDetect for the Accept-gated variant.
 *
 * @author myx */
public class WebContextXmlXhtml extends WebContextXml {

	/** @param target
	 * @param query */
	public WebContextXmlXhtml(final TargetInterface target, final ServeRequest query) {

		super(target, query);
	}

	@Override
	public ReplyAnswer getResultReply() {

		final BaseObject resultLayout = this.getResultLayout();
		if (!(resultLayout instanceof ReplyAnswer) && "xml".equals(Base.getString(resultLayout, "layout", "").trim())) {
			final String xsl = Base.getString(resultLayout, "xsl", "").trim();
			if (xsl.length() > 0) {
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
