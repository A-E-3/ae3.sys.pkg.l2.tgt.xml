package ru.myx.ae3.l2.xml;

import java.io.StringReader;

import javax.xml.transform.Templates;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.TransformerFactoryImpl;

import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseMapEditable;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.util.fn.SupplierVfsFolderMapCached;
import ru.myx.ae3.vfs.Entry;

/** Same shape as {@code ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached} (scans a flat VFS
 * folder for "*.xsl.tpl" resources, keeps a cached compiled {@link Templates} per file, keyed by
 * the public-facing file name with ".tpl" stripped) - except compiled with Saxon-HE
 * ({@link TransformerFactoryImpl}) instead of the JDK's bundled XSLTC
 * ({@code TransformerFactory.newInstance()}).
 *
 * Exists only because skin-standard-xml's own show.xsl.tpl uses a union-of-filter-expressions
 * XPath idiom that JDK XSLTC's static type-checker cannot compile - a genuine XSLTC limitation,
 * not a defect in the stylesheet; real browsers' client-side XSLT engines already handle the
 * identical construct fine, and this stylesheet has rendered correctly, unmodified, via those
 * engines for 20+ years. The original .xsl.tpl content is never changed by this class - only
 * which engine compiles the same unmodified stylesheet text.
 *
 * This is the only class in this package (and the only class anywhere) with a Saxon dependency -
 * deliberately kept local to ru.myx.ae3.l2.xml rather than touching the shared, generic
 * {@code ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached} (JDK XSLTC, used elsewhere) or
 * any other TransformerFactory call site (e.g. acm-base-sdk's AcmXsltLanguageImpl, a separate,
 * untouched mechanism).
 *
 * @author myx */
final class SupplierVfsFolderXslTemplatesCachedSaxon extends SupplierVfsFolderMapCached {

	private static final TransformerFactory transformerFactory;

	static {
		/** Plain Saxon-HE "html" output method, stock SerializerFactory - no per-Configuration
		 * override. A prior pass here (SerializerFactoryHtmlScriptStyleFix, since removed) forced
		 * <script>/<style> text through Saxon's CDATA-style raw passthrough (DISABLE_ESCAPING) for
		 * XHTML-namespaced elements, reasoning that it should match libxslt's/real-browsers' raw,
		 * unescaped '&' output for that content. That reasoning was wrong for this server's actual
		 * situation: responses render through this class are served as application/xhtml+xml, which
		 * real browsers parse as strict XML, not HTML tag-soup - a literal '&' in character data is
		 * only legal there escaped ('&amp;') or CDATA-wrapped, never raw. Confirmed live: a real
		 * bare '&' in a script literal (show.xsl.tpl's ternary '?'/'&' JS string) reached the client
		 * unescaped under the removed fix and broke Safari's real XML parser
		 * ("xmlParseEntityRef: no name"). Stock Saxon's own behavior here - not recognizing the
		 * XHTML-namespaced <script>/<style> elements as raw-text CDATA content (the same
		 * elemName.hasURI("") namespace gap the removed fix targeted) - happens to already be
		 * correct for this use case: it falls through to ordinary XML text-node escaping, i.e.
		 * exactly one level of '&' -> '&amp;', which is what well-formed XHTML requires. No
		 * override needed. See ae3.sys.pkg.l2.tgt.xml/MAGIC.md's 2026-08-27 entries for the full
		 * investigation and re-verification. */
		transformerFactory = new TransformerFactoryImpl();
	}

	/** @param folder */
	SupplierVfsFolderXslTemplatesCachedSaxon(final Entry folder) {

		super(folder);
	}

	@Override
	protected String runDescriptorFilter(final String name) {

		final String key = name.toLowerCase();
		if (key.endsWith(".xsl.tpl")) {
			return key.substring(0, key.length() - ".tpl".length());
		}
		return null;
	}

	@Override
	protected BaseObject runDescriptorMapper(final Entry entry, final String name) {

		/** One file's compile failure must not poison the whole folder's cache build - skip just
		 * this entry (matching WebContextOutputRegistry.runDescriptorMapper's per-entry-failure
		 * handling, adapted to this hierarchy's own "return null when not accepted" convention,
		 * see SupplierVfsFolderMapCached#checkReload / SupplierVfsFolderSettingsCached). The rest
		 * of the folder's templates still compile and populate the cache normally. */
		try {
			final String xslt = SupplierVfsFolderXslTemplatesCachedSaxon.stripTplWrapper(entry.getTextContent().baseValue().toString());
			final Templates templates = SupplierVfsFolderXslTemplatesCachedSaxon.transformerFactory//
					.newTemplates(new StreamSource(new StringReader(xslt)));
			return Base.forUnknown(templates);
		} catch (final Exception e) {
			return null;
		}
	}

	@Override
	protected BaseObject runDescriptorReducer(final BaseMapEditable result, final BaseObject descriptor, final String name) {

		result.putAppend(name, descriptor);
		return result;
	}

	@Override
	protected String runFolderFilter(final Entry entry) {

		/** flat scan only, no recursion into subdirectories */
		return null;
	}

	@Override
	protected BaseObject runFolderMapper(final String name, final Entry entry) {

		return null;
	}

	/** Identical to {@code ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached}'s own
	 * stripTplWrapper - ".tpl" resources are wrapped in ACM.TPL directive tags (e.g. <%FINAL:
	 * '...' %><%FORMAT: '...' %> ... <%/FORMAT%><%/FINAL%>); stripping any number of leading
	 * "<%...%>" and trailing "<%/...%>" tags recovers the raw stylesheet.
	 *
	 * @param source
	 * @return stylesheet text
	 * @throws IllegalStateException
	 *             when a leading or trailing '<%' tag is not properly closed */
	private static String stripTplWrapper(final String source) {

		final StringBuilder builder = new StringBuilder(source.trim());

		while (builder.length() > 1 && builder.charAt(0) == '<' && builder.charAt(1) == '%') {
			final int close = builder.indexOf("%>");
			if (close < 0) {
				throw new IllegalStateException("Unterminated leading '<%' tag");
			}
			builder.delete(0, close + 2);
		}

		while (builder.length() > 1 && builder.charAt(builder.length() - 1) == '>' && builder.charAt(builder.length() - 2) == '%') {
			final int open = builder.lastIndexOf("<%");
			if (open < 0) {
				throw new IllegalStateException("Unterminated trailing '%>' tag");
			}
			builder.delete(open, builder.length());
		}

		return builder.toString();
	}
}
