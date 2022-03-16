package org.folio.shared.index.util;

import org.junit.Assert;
import org.junit.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class XmlJsonUtilTest {
  @Test
  public void testGetNextSubDocument() throws XMLStreamException {
    String record1 =
        "<record>\n"
            + " <leader>1234&lt;&gt;&quot;&apos;</leader>\n"
            + "   <record>abc</record>"
            + " </record>";
    String record2 =
            "<record>\n"
            + " <leader>01010ccm a2200289   4500</leader>\n"
            + "   <controlfield tag=\"001\">a1</controlfield>\n"
            + "   <datafield tag=\"010\" ind1=\" \" ind2=\"&amp;\">\n"
            + "      <subfield code=\"a\">   70207870</subfield>\n"
            + "   </datafield>\n"
            + " </record>"
        ;
    String collection = "<collection xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
        + record1
        + "To be <ignored/>"
        + record2
        + "\n</collection>";
    InputStream stream = new ByteArrayInputStream(collection.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    String doc = XmlJsonUtil.getNextSubDocument("record", xmlStreamReader);
    Assert.assertEquals(record1, doc);
    doc = XmlJsonUtil.getNextSubDocument("record", xmlStreamReader);
    Assert.assertEquals(record2, doc);
    doc = XmlJsonUtil.getNextSubDocument("record", xmlStreamReader);
    Assert.assertNull(doc);
  }
}
