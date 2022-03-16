package org.folio.shared.index.util;

import org.junit.Assert;
import org.junit.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class XmlJsonUtilTest {
  @Test
  public void testGetSubDocumentNamespace() throws XMLStreamException {
    String collection = "<a xmlns=\"http://foo.com\">\n<b type=\"1\"><c/></b><b xmlns=\"http://bar.com\"/></a>";
    InputStream stream = new ByteArrayInputStream(collection.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);

    List<String> docs = new ArrayList<>();
    while (xmlStreamReader.hasNext()) {
      int event = xmlStreamReader.next();
      if (event == XMLStreamConstants.START_ELEMENT && "b".equals(xmlStreamReader.getLocalName())) {
        docs.add(XmlJsonUtil.getSubDocument(event, xmlStreamReader));
      }
    }
    Assert.assertEquals(2, docs.size());
    Assert.assertEquals("<b xmlns=\"http://foo.com\" type=\"1\"><c></c></b>", docs.get(0));
    Assert.assertEquals("<b xmlns=\"http://bar.com\"></b>", docs.get(1));
  }

  @Test
  public void testGetSubDocument() throws XMLStreamException {
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
    String collection = "<collection>\n"
        + record1
        + "To be <ignored/>"
        + record2
        + "\n</collection>";
    InputStream stream = new ByteArrayInputStream(collection.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);

    List<String> docs = new ArrayList<>();
    while (xmlStreamReader.hasNext()) {
      int event = xmlStreamReader.next();
      if (event == XMLStreamConstants.START_ELEMENT && "record".equals(xmlStreamReader.getLocalName())) {
        docs.add(XmlJsonUtil.getSubDocument(event, xmlStreamReader));
      }
    }
    Assert.assertEquals(2, docs.size());
    Assert.assertEquals(record1, docs.get(0));
    Assert.assertEquals(record2, docs.get(1));
  }

  @Test
  public void testMarc2DC() throws FileNotFoundException, XMLStreamException, TransformerException {
    InputStream stream = new FileInputStream("src/test/resources/record10.xml");
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    int no = 0;
    while (xmlStreamReader.hasNext()) {
      int event = xmlStreamReader.next();
      if (event == XMLStreamConstants.START_ELEMENT && "record".equals(xmlStreamReader.getLocalName())) {
        String doc = XmlJsonUtil.getSubDocument(event, xmlStreamReader);
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Source xslt = new StreamSource("src/test/resources/MARC21slim2DC.xsl");
        Transformer transformer = transformerFactory.newTransformer(xslt);
        Source source = new StreamSource(new StringReader(doc));
        StreamResult result = new StreamResult(new StringWriter());
        transformer.transform(source, result);
        String s = result.getWriter().toString();
        Assert.assertTrue(s, s.contains("<dc:title>"));
        no++;
      }
    }
    Assert.assertEquals(10, no);
  }

  @Test
  public void loadInventoryInstanceXsl()  {
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Source xslt = new StreamSource("src/test/resources/marc2inventory-instance.xsl");
    TransformerConfigurationException transformerConfigurationException =
        Assert.assertThrows(TransformerConfigurationException.class, () -> transformerFactory.newTransformer(xslt));
    System.out.println(transformerConfigurationException.getMessage());
  }
}
