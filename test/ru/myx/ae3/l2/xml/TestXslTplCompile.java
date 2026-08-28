package ru.myx.ae3.l2.xml;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.TransformerFactoryImpl;

/** Pre-deploy local check: compiles every "*.xsl.tpl" under the given roots exactly the way the
 * running server compiles them, and fails the run when any of them does not compile.
 *
 * The server does that compile lazily, on the first request that needs the skin
 * (SupplierMapAbstractCached.get -> SupplierVfsFolderMapCached.checkReload ->
 * SupplierVfsFolderXslTemplatesCachedSaxon.runDescriptorMapper), so nothing before deploy ever
 * exercised it and a broken stylesheet was reachable only as a live HTTP 500. This is that
 * exercise, on a laptop, with no build step and no server.
 *
 * Same path as runDescriptorMapper, deliberately: strip the ACM.TPL bookend, then compile with
 * Saxon-HE's own TransformerFactoryImpl - not the JDK's bundled XSLTC, not the net.sf.saxon.Transform
 * CLI. runDescriptorFilter's own rule is mirrored too: a resource counts only when its lower-cased
 * name ends ".xsl.tpl". Should the runtime ever go back to the JDK's XSLTC
 * (ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached), this file follows it.
 *
 * Run from this repo's own root, no compilation needed:
 *
 * <pre>
 * java -cp ../../lib/lib.saxonica-saxon-he/jars/Saxon-HE-9.8.0-15.jar \
 *      test/ru/myx/ae3/l2/xml/TestXslTplCompile.java
 * </pre>
 *
 * Exit status: 0 every stylesheet compiled, 1 at least one did not, 2 the check itself is not
 * trustworthy - its own controls failed, or its stripTplWrapper copy could not be checked against
 * the tracked original it was copied from.
 *
 * Nothing compiles this file: the distro source-process stage compiles only a project's own java/
 * directory, and this repo's .classpath carries no "test" source entry. Adding one would make
 * Eclipse compile this class against a project classpath holding no Saxon jar.
 *
 * @author magic-tester */
public final class TestXslTplCompile {

	/** The tracked original this file's own stripTplWrapper is a copy of, relative to this repo's
	 * own root - resolves in both the distro-source and the legacy Eclipse checkout. A separate
	 * git repository, so it is a sibling checkout rather than a path inside this one; "--origin"
	 * overrides it. */
	private static final String ORIGIN_SOURCE = "../ae3.sys.pkg.base/java/ru/myx/ae3/util/fn/SupplierVfsFolderXslTemplatesCached.java";

	/** This file's own path, relative to this repo's own root - the drift guard reads its own copy
	 * back from source rather than holding a third copy of the text to compare against. */
	private static final String OWN_SOURCE = "test/ru/myx/ae3/l2/xml/TestXslTplCompile.java";

	private static final String METHOD_OPEN = "\tprivate static String stripTplWrapper(final String source) {\n";

	private static final String METHOD_CLOSE = "\n\t}\n";

	private static final String CONTROL_BROKEN = "<%FINAL: 'text/xml' %>"
			+ "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
			+ "<xsl:template match=\"/\"><out><xsl:value-of select=\"$undeclaredOnPurpose\"/></out></xsl:template>"
			+ "</xsl:stylesheet><%/FINAL%>";

	private static final String CONTROL_SOUND = "<%FINAL: 'text/xml' %>"
			+ "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
			+ "<xsl:template match=\"/\"><out/></xsl:template>"
			+ "</xsl:stylesheet><%/FINAL%>";

	/** Saxon reports every static error it finds and only then throws, and the thrown exception's
	 * own message names none of them - the text exists solely in whatever ErrorListener is
	 * installed. Static stylesheet errors arrive as fatalError, never error. */
	private static final class Diagnostics implements ErrorListener {

		final List<String> fatal = new ArrayList<>();

		final List<String> warned = new ArrayList<>();

		@Override
		public void warning(final TransformerException e) {

			this.warned.add(e.getMessageAndLocation());
		}

		@Override
		public void error(final TransformerException e) {

			this.fatal.add(e.getMessageAndLocation());
		}

		@Override
		public void fatalError(final TransformerException e) {

			this.fatal.add(e.getMessageAndLocation());
		}
	}

	/** @param args
	 *            roots to scan; none means the current directory. "--self-test" runs the controls
	 *            and the drift guard alone and scans nothing. "--origin <path>" points the drift
	 *            guard at the tracked SupplierVfsFolderXslTemplatesCached source explicitly.
	 * @throws Exception */
	public static void main(final String[] args) throws Exception {

		final TransformerFactory factory = new TransformerFactoryImpl();
		final List<String> roots = new ArrayList<>();
		String origin = TestXslTplCompile.ORIGIN_SOURCE;
		boolean selfTestOnly = false;
		for (int at = 0; at < args.length; ++at) {
			if ("--self-test".equals(args[at])) {
				selfTestOnly = true;
			} else if ("--origin".equals(args[at]) && at + 1 < args.length) {
				origin = args[++at];
			} else {
				roots.add(args[at]);
			}
		}

		if (!TestXslTplCompile.runOwnControls(factory, origin)) {
			System.out.println("---- SELF-CHECK FAILED: this check is not trustworthy, its result means nothing");
			System.exit(2);
		}
		if (selfTestOnly) {
			System.out.println("---- self-check passed, nothing scanned");
			System.exit(0);
		}

		if (roots.isEmpty()) {
			roots.add(".");
		}
		final List<Path> targets = TestXslTplCompile.collectTargets(roots);
		int failed = 0;
		for (final Path target : targets) {
			final Diagnostics diagnostics = new Diagnostics();
			factory.setErrorListener(diagnostics);
			try {
				factory.newTemplates(new StreamSource(new StringReader(//
						TestXslTplCompile.stripTplWrapper(TestXslTplCompile.readServerText(target)))));
				System.out.println("OK    " + target + (diagnostics.warned.isEmpty()
					? ""
					: " (" + diagnostics.warned.size() + " warnings)"));
			} catch (final TransformerConfigurationException e) {
				++failed;
				System.out.println("FAIL  " + target);
				for (final String reported : diagnostics.fatal) {
					System.out.println("        " + reported);
				}
			}
		}
		System.out.println("---- " + targets.size() + " checked, " + failed + " failed, engine "
				+ factory.getClass().getName());
		System.exit(failed == 0
			? 0
			: 1);
	}

	/** Proves this run can fail and can pass before any real result is reported: a stylesheet that
	 * must not compile and must produce a captured diagnostic, one that must compile, and the
	 * stripTplWrapper copy checked against the tracked original.
	 *
	 * @param factory
	 * @param origin
	 * @return true when the check is trustworthy
	 * @throws Exception */
	private static boolean runOwnControls(final TransformerFactory factory, final String origin) throws Exception {

		boolean sound = true;

		final Diagnostics broken = new Diagnostics();
		factory.setErrorListener(broken);
		try {
			factory.newTemplates(new StreamSource(new StringReader(//
					TestXslTplCompile.stripTplWrapper(TestXslTplCompile.CONTROL_BROKEN))));
			System.out.println("CTRL  FAILED: a deliberately broken stylesheet compiled - this check cannot fail");
			sound = false;
		} catch (final TransformerConfigurationException e) {
			if (broken.fatal.isEmpty()) {
				System.out.println("CTRL  FAILED: a broken stylesheet was rejected with no diagnostic captured"
						+ " - every FAIL this run reports would be blank");
				sound = false;
			} else {
				System.out.println("CTRL  ok: a broken stylesheet is rejected, with its diagnostic captured");
			}
		}

		final Diagnostics accepted = new Diagnostics();
		factory.setErrorListener(accepted);
		try {
			factory.newTemplates(new StreamSource(new StringReader(//
					TestXslTplCompile.stripTplWrapper(TestXslTplCompile.CONTROL_SOUND))));
			System.out.println("CTRL  ok: a sound stylesheet is accepted");
		} catch (final TransformerConfigurationException e) {
			System.out.println("CTRL  FAILED: a sound stylesheet was rejected - this check cannot pass");
			sound = false;
		}

		final String tracked = TestXslTplCompile.methodSource(Paths.get(origin));
		final String own = TestXslTplCompile.methodSource(Paths.get(TestXslTplCompile.OWN_SOURCE));
		if (tracked == null || own == null) {
			System.out.println("CTRL  FAILED: stripTplWrapper could not be read back from " + origin
					+ " and " + TestXslTplCompile.OWN_SOURCE
					+ " - run from this repo's own root, or pass --origin <path>");
			sound = false;
		} else if (tracked.equals(own)) {
			System.out.println("CTRL  ok: stripTplWrapper still matches " + origin);
		} else {
			System.out.println("CTRL  FAILED: stripTplWrapper has drifted from " + origin);
			sound = false;
		}

		return sound;
	}

	/** @param source
	 * @return stripTplWrapper's own text from a .java file, or null when the file is unreadable or
	 *         the method is no longer spelled the way this guard anchors on - which is exactly when
	 *         the guard should trip rather than pass
	 * @throws Exception */
	private static String methodSource(final Path source) throws Exception {

		if (!Files.isRegularFile(source)) {
			return null;
		}
		final String text = TestXslTplCompile.readServerText(source);
		final int from = text.indexOf(TestXslTplCompile.METHOD_OPEN);
		if (from < 0) {
			return null;
		}
		final int to = text.indexOf(TestXslTplCompile.METHOD_CLOSE, from);
		if (to <= from) {
			return null;
		}
		return text.substring(from, to + TestXslTplCompile.METHOD_CLOSE.length());
	}

	/** Decodes the same way AE3's own VFS does when the server reads these resources - UTF-8, and
	 * lenient, so a malformed byte becomes U+FFFD and the file still reaches the compiler exactly
	 * as it would in production. Files.readString is strict and would turn a servable file into an
	 * IOException instead of a real verdict. A UTF-8 BOM is deliberately left in place: it survives
	 * trim() on the server too, and is rejected there too.
	 *
	 * @param source
	 * @return file text
	 * @throws Exception */
	private static String readServerText(final Path source) throws Exception {

		return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
	}

	/** @param roots
	 * @return every "*.xsl.tpl" under roots, in a stable order, applying runDescriptorFilter's own
	 *         lower-cased-suffix rule
	 * @throws Exception */
	private static List<Path> collectTargets(final List<String> roots) throws Exception {

		final List<Path> targets = new ArrayList<>();
		for (final String root : roots) {
			try (Stream<Path> walk = Files.walk(Paths.get(root))) {
				walk.filter(Files::isRegularFile)//
						.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xsl.tpl"))//
						.sorted()//
						.forEach(targets::add);
			}
		}
		return targets;
	}

	/** Copied verbatim from SupplierVfsFolderXslTemplatesCachedSaxon, itself identical to
	 * ru.myx.ae3.util.fn.SupplierVfsFolderXslTemplatesCached's own - a re-implementation here would
	 * be free to drift from the one the server actually runs, invisibly. runOwnControls checks this
	 * copy against that tracked original on every run.
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

	private TestXslTplCompile() {

		// static utility, not instantiable
	}
}
