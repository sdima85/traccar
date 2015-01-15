/*

 */
package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.FrameDecoder;

public class BitrakFrameDecoder extends FrameDecoder {

    private static final int MESSAGE_MINIMUM_LENGTH = 12;
    
    @Override
    protected Object decode(
            ChannelHandlerContext ctx,
            Channel channel,
            ChannelBuffer buf) throws Exception {
        
        // Check minimum length
        if (buf.readableBytes() < MESSAGE_MINIMUM_LENGTH) {
            return null;
        }

        // Read packet
        int length = buf.getUnsignedShort(buf.readerIndex());
        if (length > 0) {
            if (buf.readableBytes() >= (length + 2)) {
                return buf.readBytes(length + 2);
            }
        } else if((length==0) && (buf.getUnsignedShort(buf.readerIndex()+2)>0)) {
            // Config packet
            int dataLength = buf.getUnsignedShort(buf.readerIndex() + 2);
            if (buf.readableBytes() >= (dataLength + 6)) {
                return buf.readBytes(dataLength + 6);
            }
        } else {
            // Position packet
            int dataLength = buf.getInt(buf.readerIndex() + 4);
            if (buf.readableBytes() >= (dataLength + 12)) {
                return buf.readBytes(dataLength + 12);
            }
        }
        
        return null;
    }

}
