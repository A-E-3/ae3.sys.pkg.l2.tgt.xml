package ru.myx.ae3.l2.xml;

import ru.myx.ae3.answer.Reply;
import ru.myx.ae3.answer.ReplyAnswer;
import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.binary.TransferCopier;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.i3.web.WebContext;
import ru.myx.ae3.l2.NativeTargetContext;
import ru.myx.ae3.serve.ServeRequest;
import ru.myx.ae3.xml.Xml;

/** @author myx */
public class WebContextXml extends NativeTargetContext implements WebContext<NativeTargetContext> {

	ServeRequest query;

	/** @param target
	 * @param query
	 */
	public WebContextXml(final TargetInterface target, final ServeRequest query) {

		super(target, NativeTargetContext.TargetMode.CLONE);
		this.query = query;
	}

	@Override
	public ServeRequest getQuery() {

		return this.query;
	}

	@Override
	public ReplyAnswer getResultReply() {

		final BaseObject resultLayout = this.getResultLayout();
		if (resultLayout instanceof ReplyAnswer) {
			return (ReplyAnswer) resultLayout;
		}
		assert resultLayout != null : "NULL layout";
		final String layout = Base.getString(this.result, "layout", "").trim();
		if (layout.length() > 0) {
			if ("xml".equals(layout)) {
				final int code = Base.getInt(this.result, "code", 200);
				final String xsl = Base.getString(this.result, "xsl", "").trim();
				return Reply.string(
						this.getClass().getSimpleName(), //
						this.query,
						"<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //
								+ (xsl.length() == 0
									? ""
									: "<?xml-stylesheet type=\"text/xsl\" href=\"" + xsl + "\"?>") //
								+ Base.getString(this.result, "content", "<none/>")) //
						.setCode(code)//
						.setAttribute("Content-Type", "text/xml")//
						.setFinal();
			}
			if ("final".equals(layout) && "text/xml".equals(Base.getString(this.result, "type", ""))) {
				final int code = Base.getInt(this.result, "code", 200);
				return Reply.string(
						this.getClass().getSimpleName(), //
						this.query,
						Base.getString(this.result, "content", "<none/>")) //
						.setCode(code)//
						.setAttribute("Content-Type", "text/xml")//
						.setFinal();
			}
		}
		final TransferCopier binary = Xml.toXmlBinary("layout", this.result, true, null, null, 0);
		return Reply.binary(
				this.getClass().getSimpleName(), //
				this.query,
				binary) //
				.setAttribute("Content-Type", "text/xml")//
				.setFinal();
	}
}
