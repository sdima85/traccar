/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.ChannelBufferTools;
import org.traccar.helper.Log;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

import java.util.Date;
import java.util.Properties;

public class UlbotechProtocolDecoder extends BaseProtocolDecoder {

    public UlbotechProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private static final short DATA_GPS = 0x01;
    private static final short DATA_LBS = 0x02;
    private static final short DATA_STATUS = 0x03;
    private static final short DATA_MILAGE = 0x04;
    private static final short DATA_ADC = 0x05;
    private static final short DATA_GEOFENCE = 0x06;
    private static final short DATA_OBD2 = 0x07;
    private static final short DATA_FUEL = 0x08;
    private static final short DATA_OBD2_ALARM = 0x09;
    private static final short DATA_HARSH_DRIVER = 0x0A;
    private static final short DATA_CANBUS = 0x0B;

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        buf.readByte(); // header
        buf.readUnsignedByte(); // version
        buf.readUnsignedByte(); // type

        // Create new position
        Position position = new Position();
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());

        // Get device id
        String imei = ChannelBufferTools.readHexString(buf, 16).substring(1);
        try {
            position.setDeviceId(getDataManager().getDeviceByImei(imei).getId());
        } catch(Exception error) {
            Log.warning("Unknown device - " + imei);
        }

        // Time
        long seconds = buf.readUnsignedInt() & 0x7fffffffl;
        seconds += 946684800l; // 2000-01-01 00:00
        position.setTime(new Date(seconds * 1000));

        while (buf.readableBytes() > 3) {

            short type = buf.readUnsignedByte();
            short length = buf.readUnsignedByte();

            switch (type) {

                case DATA_GPS:

                    position.setValid(true);
                    position.setLatitude(buf.readInt() / 1000000.0);
                    position.setLongitude(buf.readInt() / 1000000.0);
                    position.setAltitude(0.0);
                    position.setSpeed(buf.readUnsignedShort() * 0.539957);
                    position.setCourse((double) buf.readUnsignedShort());
                    extendedInfo.set("hdop", buf.readUnsignedShort());

                    break;


                default:
                    buf.skipBytes(length);
                    break;
            }
        }

        position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return position;
    }

}
