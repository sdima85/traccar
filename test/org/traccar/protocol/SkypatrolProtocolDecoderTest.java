package org.traccar.protocol;

import org.traccar.helper.TestDataManager;
import org.jboss.netty.buffer.ChannelBuffers;
import static org.traccar.helper.DecoderVerifier.verify;
import org.junit.Test;
import org.traccar.helper.ChannelBufferTools;

public class SkypatrolProtocolDecoderTest {

    @Test
    public void testDecode() throws Exception {

        SkypatrolProtocolDecoder decoder = new SkypatrolProtocolDecoder(new TestDataManager(), null, null);

        verify(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
                "0005021004FFFFFFFF0000000D313134373735383300CB000000000E11070C010184D032FB3841370000000016072B000017050032000000000000024E0C071116072C105900050000000000050000000000050000000003100260B7363B6306C11A00B73637F206BF19B73637F106B50EB73638B106BB0BB7363B6106B80AB73637F306B709000000000000000000C"))));

        //verify(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
        //        "000500030101383637383434303031373832333336420102000c0000fa07b5e101876c5b0e0a111606131c1b5e"))));

        //Enfora TT8750
        //verify(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
        //        "000502000000f1143035303031393031d1df002f00000d0187120115e556ff762aa90000000000aae40005d2000ee1bc0e010a042530000000000000070004000002233c096c00ee2a00233c008500f022233c0b0500f21d233c000000fb23000000000000000000000000000000000000000000000000000000"))));

        //verify(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
        //        "00040200202020202020202020382020202020202030313137323230303131383531373820313220244750524d432c3232343833392e30302c412c303332382e3433383830362c4e2c30373633312e3630373731372c572c302e302c302e302c3139303731342c332e382c452c412a32420d0a00"))));

    }

}
