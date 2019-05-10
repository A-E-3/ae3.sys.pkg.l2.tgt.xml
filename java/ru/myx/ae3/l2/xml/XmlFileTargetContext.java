package ru.myx.ae3.l2.xml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import ru.myx.ae3.binary.Transfer;
import ru.myx.ae3.binary.TransferCopier;
import ru.myx.ae3.i3.TargetInterface;
import ru.myx.ae3.l2.NativeTargetContext;
import ru.myx.ae3.xml.Xml;

/** @author myx */
public class XmlFileTargetContext extends NativeTargetContext {

	private final File file;
	
	/** @param file
	 */
	public XmlFileTargetContext(final File file) {
		
		super((TargetInterface) null, NativeTargetContext.TargetMode.CLONE);
		this.file = file;
	}
	
	@Override
	public void doFinish() {

		try {
			final TransferCopier binary = Xml.toXmlBinary("layout", this.result, true, null, null, 0);
			final FileOutputStream output = new FileOutputStream(this.file);
			Transfer.toStream(binary.nextCopy(), output, true);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
}
