package ru.myx.ae3.l2.xml;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;

import ru.myx.ae3.base.Base;
import ru.myx.ae3.base.BaseMapEditable;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.util.fn.SupplierVfsFolderMapCached;
import ru.myx.ae3.vfs.Entry;

/** Same shape as {@code ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached} (scans
 * skin-standard-xml's flat VFS folder for "*.xsl.tpl", caches a compiled {@link XsltExecutable}
 * per file, keyed by public file name with ".tpl" stripped), but compiled via Saxon-HE's own
 * s9api ({@link Processor}/{@link XsltCompiler}) instead of the JDK's bundled XSLTC, which cannot
 * compile show.xsl.tpl's union-of-filter-expressions XPath idiom. The only Saxon-dependent class
 * in this package. Full rationale: this package's {@code MAGIC.md}.
 *
 * @author myx */
final class SupplierVfsFolderXslTemplatesCachedSaxon extends SupplierVfsFolderMapCached {

	private static final Processor processor = new Processor(false);

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

		// one file's compile failure must not poison the whole folder - skip just this entry, return null
		try {
			final String xslt = SupplierVfsFolderXslTemplatesCachedSaxon.stripTplWrapper(entry.getTextContent().baseValue().toString());
			final XsltCompiler compiler = SupplierVfsFolderXslTemplatesCachedSaxon.processor.newXsltCompiler();
			final XsltExecutable executable = compiler.compile(new StreamSource(new java.io.StringReader(xslt)));
			return Base.forUnknown(executable);
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

	/** Identical to {@code SupplierVfsFolderXslTemplatesCached}'s own stripTplWrapper: strips
	 * leading/trailing ACM.TPL directive tags (e.g. {@code <%FINAL:'...'%>...<%/FINAL%>}) to
	 * recover the raw stylesheet.
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
