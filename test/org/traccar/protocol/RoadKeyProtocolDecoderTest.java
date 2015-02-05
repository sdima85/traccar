package org.traccar.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.traccar.helper.TestDataManager;
import static org.traccar.helper.DecoderVerifier.verify;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class RoadKeyProtocolDecoderTest {

    @Test
    public void testDecode() throws Exception {
        RoadKeyProtocolDecoder decoder = new RoadKeyProtocolDecoder(new TestDataManager(), null, null);
        
        assertNull(decoder.decode(null, null, "!NM311-06080801023 Nadiya 1.9c.4012 (c) Road Key"));        
        assertNull(decoder.decode(null, null, "\u0002353976014438992\u0003"));
        
        //assertNull(decoder.decode(null, null, "$PRKA,8E,,083130,,im*0A"));
        assertNull(decoder.decode(null, null, "$PRKA,8E,240913,083130,S,im*0A"));
        assertNull(decoder.decode(null, null, "$PRKB,1,=EjMAkAAJAAAPiAzdABEAAAABK94AAAAUAAAArgDP//4=*3D"));
        assertNull(decoder.decode(null, null, "$GPGGA,083130,4849.266,N,03301.323,E,1,5,3.6,135.3,M*2C"));
        assertNull(decoder.decode(null, null, "$PRKZ,S*6C"));
        verify(decoder.decode(null, null,"\u0002233615\u0003"));
        
    }

}
