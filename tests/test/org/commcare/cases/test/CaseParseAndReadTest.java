package org.commcare.cases.test;

import org.commcare.test.utils.TestInstanceInitializer;
import org.commcare.test.utils.XmlComparator;
import org.commcare.util.mocks.MockDataUtils;
import org.commcare.util.mocks.MockUserDataSandbox;
import org.javarosa.core.io.StreamsUtil;
import org.javarosa.core.model.instance.ExternalDataInstance;
import org.javarosa.model.xform.DataModelSerializer;
import org.junit.Before;
import org.junit.Test;
import org.kxml2.kdom.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;

/**
 * Test suite to verify end-to-end parsing of inbound case XML
 * and reading values back from the casedb model
 *
 * @author ctsims
 */
public class CaseParseAndReadTest {

    private MockUserDataSandbox sandbox;

    @Before
    public void setUp() {
        sandbox = MockDataUtils.getStaticStorage();
    }

    @Test
    public void testReadCaseDB() throws IOException {
        compareCaseDbState("/case_create_basic.xml", "/case_create_basic_output.xml");
    }

    private void compareCaseDbState(String inputTransactions, String caseDbState) throws IOException {
        MockDataUtils.parseIntoSandbox(this.getClass().getResourceAsStream(inputTransactions), sandbox);

        byte[] parsedDb = dumpInstance("jr://instance/casedb");
        Document parsed = XmlComparator.getDocumentFromStream(new ByteArrayInputStream(parsedDb));
        Document loaded = XmlComparator.getDocumentFromStream(this.getClass().getResourceAsStream(caseDbState));

        try {
            XmlComparator.compareXmlDOMs(parsed, loaded);
        } catch (Exception e) {
            System.out.print(new String(parsedDb));


            assertEquals("CaseDB output did not match expected structure(" + e.getMessage() + ")", new String(dumpStream(caseDbState)), new String(parsedDb));
        }
    }

    private byte[] dumpInstance(String instanceRef) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataModelSerializer s = new DataModelSerializer(bos, new TestInstanceInitializer(sandbox));

            s.serialize(new ExternalDataInstance(instanceRef, "instance"), null);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private byte[] dumpStream(String inputResource) throws IOException {
        InputStream is = this.getClass().getResourceAsStream(inputResource);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        StreamsUtil.writeFromInputToOutput(is, bos);

        return bos.toByteArray();
    }
}
