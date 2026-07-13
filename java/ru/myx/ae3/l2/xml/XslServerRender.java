package ru.myx.ae3.l2.xml;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.transform.Templates;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.serve.ServeRequest;
import ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached;
import ru.myx.ae3.vfs.Storage;

/** Shared server-side XSLT rendering for skin-standard-xml, used by both
 * {@link WebContextXmlAutoDetect} (Accept-gated) and {@link WebContextXmlXhtml} (unconditional).
 *
 * @author myx */
final class XslServerRender {

	/** compiled Templates for every "*.xsl.tpl" resource in skin-standard-xml, keyed by public
	 * file name (".tpl" stripped, e.g. "show.xsl.tpl" -> "show.xsl") - matching what result.xsl
	 * actually ends with. Scanned from /union (not just /public) so an override in a
	 * higher-priority VFS tier is picked up. */
	private static final SupplierVfsFolderXslTemplatesCached xslTemplates = new SupplierVfsFolderXslTemplatesCached(//
			Storage.UNION.relative("resources/skin/skin-standard-xml", null));

	/** @param query
	 * @return true when the client's Accept header lists application/xhtml+xml */
	static boolean acceptsXhtml(final ServeRequest query) {

		return Base.getString(query.getAttributes(), "Accept", "").contains("application/xhtml+xml");
	}

	/** @param xsl
	 *            result.xsl value, used to look up a scanned stylesheet by file name
	 * @param xml
	 * @return transformed xhtml, or null when xsl doesn't name a known stylesheet, the stylesheet
	 *         isn't compiled yet, or the transform failed (caller falls back to the plain
	 *         client-PI/text/xml reply in that case) */
	static String transform(final String xsl, final String xml) {

		try {
			final Object templates = XslServerRender.xslTemplates.get()//
					.baseGet(XslServerRender.fileName(xsl), BaseObject.UNDEFINED)//
					.baseValue();
			if (!(templates instanceof Templates)) {
				return null;
			}
			final StringWriter writer = new StringWriter();
			((Templates) templates).newTransformer().transform(//
					new StreamSource(new StringReader(xml)), //
					new StreamResult(writer));
			return writer.toString();
		} catch (final Exception e) {
			return null;
		}
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
