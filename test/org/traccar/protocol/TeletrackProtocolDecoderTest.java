package org.traccar.protocol;

import java.nio.charset.Charset;
import org.traccar.helper.TestDataManager;
import org.jboss.netty.buffer.ChannelBuffers;
import static org.traccar.helper.DecoderVerifier.verify;
import static org.junit.Assert.assertNull;
import org.junit.Test;
import org.traccar.helper.ChannelBufferTools;

public class TeletrackProtocolDecoderTest {

    @Test
    public void testDecode() throws Exception {

        TeletrackProtocolDecoder decoder = new TeletrackProtocolDecoder(new TestDataManager(), null, null);
        //AUNT
        //assertNull(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
        //        "2525415533304344003330434400"))));
        
        //BIN 26900d003ac0c05464b22c008d3b1e0000000018b066000000000000020000f3
        //assertNull(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
        //        "26900d003ac0c05464b22c008d3b1e0000000018b066000000000000020000f3"))));
        
        //MultiBin
        
        
        //PackedMultiBin
        //assertNull(decoder.decode(null, null, ChannelBuffers.wrappedBuffer(ChannelBufferTools.convertHexString(
                //       BB
        //        "252550423BD9280D00B54B6D38000000000000000000000010F00000000003A400040000008C00102EDABB4300348006088800000EDBBD04000088000028DCF93C068C000028DD354440048C00002EDEBB43448002088800002EDFBD4804000088000008E0F9068C000028E135444C0488000028E271500688000008E3AD0488000028E4EA54068C000008E526450488000028E662580688000008E79E048C00002EE8BB43540002088800000EE9BD04000088001008EAF910068C000008EB35440488000008EC3D0288000008ED670488000008EE6F0288000008EF990488000008F0A10288000008F1CB0488000008F2D3028C00102EF3BB43005886020C8800002EF4E65C0600008C001000F518441088000020F64A608800000CF75100028800000CF87C060088000000F9AE88000020FAE0648800000CFBE400028C00000CFC1245060088000000FD4488000000FE768800000CFF7A0002C800000C0029A806008800002001DA688C000000020C468800000C030F00028800000C043E06008800000005708800000006A28800000C07A600028800000C08D406008C000000090647880000000A388800000C0B3C00028800002C0C6A6C0600880000000D9C880000000ECE8800000C0FD500028C00000C10004806008800000011328800000012648800000C1367000288"))));
        
        
        // A1 EventConfigSet
        assertNull(decoder.decode(null, null, 
             ChannelBuffers.wrappedBuffer(
                     stringToBytesASCII
                (
                //"d000@k314.net %%AAi<zz5MDABxAAPDA6JzEAAQABAEBAARABAEBAABABAEBABRAAAEBAADAAAEAAARAAAAAABRABAEBAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPAHIAAAXX8XXX-XXX-XXX-XXX-XXX-XXX-"
                "              %%AAi0z>wwMAFzcbdHcLa4MMNHLbZadAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAdYa1LZZedbeUacLhYbbfAAAAAAAAAAAAANMwRMABAAAAAANAMRMHAAAAAAAAXXX-XXX-XXX-"
                        
                //"d000@k314.net %%"+
                //"..i10>yuFAATAACjAZAw6AAQABAEBAARABAEBAABABAEBABRAAAEBAADAAAE"+
                //"AAARAAAAAABRABAEAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPCDEACAXX8"+
                //"XXX-XXX-XXX-XXX-XXX-XXX-"
                //"d000@k314.net %%..i<wy5iFAIDAACjAZAw6AAQABAEBAARABAEBAABAHAEHAHRAGAUHAGTAGAEGAGRAEAVEABRABAEBAABAHAEAAAAAAAAAAAAAAAAAAAAAAAABAWIDEACAXX8XXX-XXX-XXX-XXX-XXX-XXX-"
                )
                //.getBytes("US-ASCII") 
                //getBytes(Charsets.US_ASCII)//Charset.defaultCharset())
             )));
    }
    
    public static byte[] stringToBytesASCII(String str) {
        byte[] b = new byte[str.length()];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) str.charAt(i);
        }
        return b;
    }


}
