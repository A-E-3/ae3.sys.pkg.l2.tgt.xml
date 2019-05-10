package ru.myx.ae3.l2.xml;

import java.io.File;
import java.io.FileInputStream;

import ru.myx.ae3.Engine;
import ru.myx.ae3.base.BaseObject;
import ru.myx.ae3.l2.LayoutEngine;

/**
 * @author myx
 * 
 */
public class TestXml {
	
	/**
	 * @param args
	 * @throws Throwable
	 */
	public static void main(final String[] args) throws Throwable {
		Engine.createGuid();
		
		final File file = File.createTempFile( "xmltest-", ".xml" );
		System.out.println( "LayoutEngine2 XML renderer test, output to: " + file.getAbsolutePath() );
		
		final BaseObject text = args == null || args.length == 0
				? LayoutEngine.getDocumentation() // context.getLayoutAbout()
				: args.length > 1
						? LayoutEngine.getDocumentation()
						: LayoutEngine.parseJSLD( new FileInputStream( args[0] ) );
		new XmlFileTargetContext( file ).transform( text ).baseValue();
		
		Engine.createProcess( file.getName(), null, file.getParentFile() );
	}
}
