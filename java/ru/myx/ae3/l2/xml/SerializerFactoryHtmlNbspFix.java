package ru.myx.ae3.l2.xml;

import java.io.IOException;
import java.util.Properties;

import net.sf.saxon.Configuration;
import net.sf.saxon.event.ReceiverOptions;
import net.sf.saxon.expr.parser.Location;
import net.sf.saxon.lib.SaxonOutputKeys;
import net.sf.saxon.lib.SerializerFactory;
import net.sf.saxon.serialize.Emitter;
import net.sf.saxon.serialize.HTML40Emitter;
import net.sf.saxon.serialize.HTML50Emitter;
import net.sf.saxon.trans.XPathException;

/** Works around a Saxon-HE bug: {@code net.sf.saxon.serialize.HTMLEmitter} hardcodes U+00A0
 * (NO-BREAK SPACE) as the literal named entity {@code &nbsp;}, illegal in the DTD-less
 * {@code application/xhtml+xml} output this class serializes for.
 *
 * Hooks Saxon's documented {@code SerializerFactory.newHTMLEmitter(Properties)} extension point
 * to subclass whichever stock HTML4/HTML5 emitter Saxon would otherwise pick, overriding
 * {@code characters(...)} to intercept only U+00A0 and emit it as a numeric character reference
 * instead - {@code writeEscape} itself can't be overridden directly, since it reads fields not
 * visible outside {@code net.sf.saxon.serialize}. Every other character, and any content already
 * serialized with escaping disabled (e.g. inside {@code <script>}/{@code <style>}), passes
 * straight through to Saxon's own stock handling unchanged.
 *
 * @author myx */
final class SerializerFactoryHtmlNbspFix extends SerializerFactory {

	/** Unicode code point for NO-BREAK SPACE. */
	private static final int NBSP = 160;

	SerializerFactoryHtmlNbspFix(final Configuration config) {

		super(config);
	}

	/** @param chars
	 *            text to scan
	 * @param from
	 *            index to start scanning at
	 * @return index of the next U+00A0 in {@code chars} at or after {@code from}, or -1 when none
	 *         remain */
	private static int indexOfNbsp(final CharSequence chars, final int from) {

		final int length = chars.length();
		for (int i = from; i < length; i++) {
			if (chars.charAt(i) == SerializerFactoryHtmlNbspFix.NBSP) {
				return i;
			}
		}
		return -1;
	}

	@Override
	protected Emitter newHTMLEmitter(final Properties properties) {

		return SaxonOutputKeys.isHtmlVersion5(properties)
			? new Html50EmitterNbspFix()
			: new Html40EmitterNbspFix();
	}

	/** {@code html-version="4.0"} variant (Saxon's own default, since show.xsl.tpl sets no
	 * {@code html-version}/{@code version} output property). */
	private static final class Html40EmitterNbspFix extends HTML40Emitter {

		@Override
		public void characters(final CharSequence chars, final Location locationId, final int properties) throws XPathException {

			if ((properties & (ReceiverOptions.DISABLE_ESCAPING | ReceiverOptions.NO_SPECIAL_CHARS)) != 0) {
				super.characters(chars, locationId, properties);
				return;
			}

			final int length = chars.length();
			int at = SerializerFactoryHtmlNbspFix.indexOfNbsp(chars, 0);
			if (at < 0) {
				super.characters(chars, locationId, properties);
				return;
			}

			int start = 0;
			while (at >= 0) {
				if (at > start) {
					super.characters(chars.subSequence(start, at), locationId, properties);
				}
				try {
					this.characterReferenceGenerator.outputCharacterReference(SerializerFactoryHtmlNbspFix.NBSP, this.writer);
				} catch (final IOException e) {
					throw new XPathException(e);
				}
				start = at + 1;
				at = start < length
					? SerializerFactoryHtmlNbspFix.indexOfNbsp(chars, start)
					: -1;
			}
			if (start < length) {
				super.characters(chars.subSequence(start, length), locationId, properties);
			}
		}
	}

	/** Same fix as {@link Html40EmitterNbspFix}, for the {@code html-version="5.0"} case - not
	 * currently reached by show.xsl.tpl, kept consistent with Saxon's own default dispatch so this
	 * fix does not silently regress if that ever changes. */
	private static final class Html50EmitterNbspFix extends HTML50Emitter {

		@Override
		public void characters(final CharSequence chars, final Location locationId, final int properties) throws XPathException {

			if ((properties & (ReceiverOptions.DISABLE_ESCAPING | ReceiverOptions.NO_SPECIAL_CHARS)) != 0) {
				super.characters(chars, locationId, properties);
				return;
			}

			final int length = chars.length();
			int at = SerializerFactoryHtmlNbspFix.indexOfNbsp(chars, 0);
			if (at < 0) {
				super.characters(chars, locationId, properties);
				return;
			}

			int start = 0;
			while (at >= 0) {
				if (at > start) {
					super.characters(chars.subSequence(start, at), locationId, properties);
				}
				try {
					this.characterReferenceGenerator.outputCharacterReference(SerializerFactoryHtmlNbspFix.NBSP, this.writer);
				} catch (final IOException e) {
					throw new XPathException(e);
				}
				start = at + 1;
				at = start < length
					? SerializerFactoryHtmlNbspFix.indexOfNbsp(chars, start)
					: -1;
			}
			if (start < length) {
				super.characters(chars.subSequence(start, length), locationId, properties);
			}
		}
	}
}
