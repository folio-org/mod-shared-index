package org.folio.shared.index.util;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.junit.Assert;
import org.junit.Test;
import org.xml.sax.SAXException;

public class XmlJsonUtilTest {
  static final String MARCXML1_SAMPLE =
      "<record>\n"
          + " <leader>1234&lt;&gt;&quot;&apos;</leader>\n"
          + "   <record>abc</record>"
          + " </record>";

  static final String MARCXML2_SAMPLE =
      "<record>\n"
          + " <leader>01010ccm a2200289   4500</leader>\n"
          + "   <controlfield tag=\"001\">a1</controlfield>\n"
          + "   <datafield tag=\"010\" ind1=\" \" ind2=\"&amp;\">\n"
          + "      <subfield code=\"a\">   70207870</subfield>\n"
          + "   </datafield>\n"
          + "   <datafield tag=\"245\">\n"
          + "      <subfield code=\"a\">Title</subfield>\n"
          + "   </datafield>\n"
          + " </record>";

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
  public void testGetSubDocumentCollection() throws XMLStreamException {
    String collection = "<collection>\n"
        + MARCXML1_SAMPLE
        + "To be <ignored/>"
        + MARCXML2_SAMPLE
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
    Assert.assertEquals(MARCXML1_SAMPLE, docs.get(0));
    Assert.assertEquals(MARCXML2_SAMPLE, docs.get(1));
  }

  @Test
  public void testGetSubDocumentNoSub() throws XMLStreamException {
    String collection = "<tag>x</tag>";
    InputStream stream = new ByteArrayInputStream(collection.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);

    int event;
    Assert.assertTrue(xmlStreamReader.hasNext());
    xmlStreamReader.next();
    Assert.assertTrue(xmlStreamReader.hasNext());
    event = xmlStreamReader.next();
    Assert.assertNull(XmlJsonUtil.getSubDocument(event, xmlStreamReader));
  }

  @Test
  public void testGetSubDocumentDocType() throws XMLStreamException {
    String sub = "<tag>x</tag>";
    String collection = "<!DOCTYPE tag []>" + sub;
    InputStream stream = new ByteArrayInputStream(collection.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    int event = xmlStreamReader.next();
    Assert.assertEquals(sub, XmlJsonUtil.getSubDocument(event, xmlStreamReader));
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
        if (doc == null) {
          break;
        }
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
  public void testMarc2Inventory() throws FileNotFoundException, XMLStreamException, TransformerException {
    InputStream stream = new FileInputStream("src/test/resources/record10.xml");
    XMLInputFactory factory = XMLInputFactory.newInstance();
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    int no = 0;
    while (xmlStreamReader.hasNext()) {
      int event = xmlStreamReader.next();
      if (event == XMLStreamConstants.START_ELEMENT && "record".equals(xmlStreamReader.getLocalName())) {
        String doc = XmlJsonUtil.getSubDocument(event, xmlStreamReader);
        if (doc == null) {
          break;
        }
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Source xslt = new StreamSource("src/test/resources/marc2inventory-instance.xsl");
        Transformer transformer = transformerFactory.newTransformer(xslt);
        Source source = new StreamSource(new StringReader(doc));
        StreamResult result = new StreamResult(new StringWriter());
        transformer.transform(source, result);
        String s = result.getWriter().toString();
        Assert.assertTrue(s, s.contains("<localIdentifier>"));
        no++;
      }
    }
    Assert.assertEquals(10, no);
  }

  @Test
  public void convertMarcXmlToJsonRecord1() throws ParserConfigurationException, IOException, SAXException {
    JsonObject got = XmlJsonUtil.convertMarcXmlToJson(MARCXML1_SAMPLE);
    JsonObject expected = new JsonObject().put("leader","1234<>\"'");
    Assert.assertEquals(expected, got);
    String collection = "<collection>" + MARCXML1_SAMPLE + "</collection>";
    got = XmlJsonUtil.convertMarcXmlToJson(collection);
    Assert.assertEquals(expected, got);
  }

  @Test
  public void convertMarcXmlToJsonRecord2() throws ParserConfigurationException, IOException, SAXException {
    JsonObject got = XmlJsonUtil.convertMarcXmlToJson(MARCXML2_SAMPLE);
    JsonObject expected = new JsonObject()
        .put("leader", "01010ccm a2200289   4500")
        .put("fields", new JsonArray()
            .add(new JsonObject().put("001", "a1"))
            .add(new JsonObject().put("010", new JsonObject()
                    .put("ind1", " ")
                    .put("ind2", "&")
                    .put("subfields", new JsonArray()
                        .add(new JsonObject()
                            .put("a", "   70207870"))
                    )
                )
            )
            .add(new JsonObject().put("245", new JsonObject()
                    .put("subfields", new JsonArray()
                        .add(new JsonObject()
                            .put("a", "Title"))
                    )
                )
            )
        );
    Assert.assertEquals(expected, got);
    String collection = "<collection>" + MARCXML2_SAMPLE + "</collection>";
    got = XmlJsonUtil.convertMarcXmlToJson(collection);
    Assert.assertEquals(expected, got);
  }

  @Test
  public void convertMarcXmlToJsonRecordMulti() {
    String collection = "<collection>" + MARCXML1_SAMPLE + MARCXML2_SAMPLE + "</collection>";
    Throwable t = Assert.assertThrows(IllegalArgumentException.class,
        () ->XmlJsonUtil.convertMarcXmlToJson(collection));
    Assert.assertEquals("can not handle multiple records", t.getMessage());
  }

  @Test
  public void convertMarcXmlToJsonRecordMissing()  {
    String record = "<foo/>";
    Throwable t = Assert.assertThrows(IllegalArgumentException.class,
        () ->XmlJsonUtil.convertMarcXmlToJson(record));
    Assert.assertEquals("No record element found", t.getMessage());

    String collection = "<collection><foo/></collection>";
    t = Assert.assertThrows(IllegalArgumentException.class,
        () ->XmlJsonUtil.convertMarcXmlToJson(collection));
    Assert.assertEquals("No record element found", t.getMessage());
  }

}
