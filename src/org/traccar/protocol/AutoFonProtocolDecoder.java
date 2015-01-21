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
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.ChannelBufferTools;
import org.traccar.helper.Log;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

import java.util.*;

public class AutoFonProtocolDecoder extends BaseProtocolDecoder {

    public AutoFonProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private static final int MSG_LOGIN = 0x10;
    private static final int MSG_LOCATION = 0x11;
    private static final int MSG_HISTORY = 0x12;

    private long deviceId;

    private static double convertCoordinate(int raw) {
        double result = raw / 1000000;
        result += (raw % 1000000) / 600000.0;
        return result;
    }

    private Position decodePosition(ChannelBuffer buf, boolean history) {

        // Create new position
        Position position = new Position();
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        position.setDeviceId(deviceId);

        if (!history) {
            buf.readUnsignedByte(); // interval
            buf.skipBytes(8); // settings
        }
        buf.readUnsignedByte(); // status
        if (!history) {
            buf.readUnsignedShort();
        }
        extendedInfo.set("battery", buf.readUnsignedByte());
        buf.skipBytes(6); // time

        // Timers
        if (!history) {
            for (int i = 0; i < 2; i++) {
                buf.skipBytes(5); // time
                buf.readUnsignedShort(); // interval
                buf.skipBytes(5); // mode
            }
        }

        extendedInfo.set("temperature", buf.readByte());
        extendedInfo.set("gsm", buf.readUnsignedByte());
        buf.readUnsignedShort(); // mcc
        buf.readUnsignedShort(); // mnc
        buf.readUnsignedShort(); // lac
        buf.readUnsignedShort(); // cid

        // GPS status
        int valid = buf.readUnsignedByte();
        position.setValid((valid & 0xc0) != 0);
        extendedInfo.set("satellites", valid & 0x3f);

        // Date and time
        Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        time.clear();
        time.set(Calendar.DAY_OF_MONTH, buf.readUnsignedByte());
        time.set(Calendar.MONTH, buf.readUnsignedByte() - 1);
        time.set(Calendar.YEAR, 2000 + buf.readUnsignedByte());
        time.set(Calendar.HOUR_OF_DAY, buf.readUnsignedByte());
        time.set(Calendar.MINUTE, buf.readUnsignedByte());
        time.set(Calendar.SECOND, buf.readUnsignedByte());
        position.setTime(time.getTime());

        // Location
        position.setLatitude(convertCoordinate(buf.readInt()));
        position.setLongitude(convertCoordinate(buf.readInt()));
        position.setAltitude((double) buf.readShort());
        position.setSpeed((double) buf.readUnsignedByte());
        position.setCourse(buf.readUnsignedByte() * 2.0);

        extendedInfo.set("hdop", buf.readUnsignedShort());

        buf.readUnsignedShort(); // reserved
        buf.readUnsignedByte(); // checksum

        position.setExtendedInfo(extendedInfo.toString());
        return position;
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        int type = buf.readUnsignedByte();

        if (type == MSG_LOGIN) {

            buf.readUnsignedByte(); // hardware version
            buf.readUnsignedByte(); // software version

            String imei = ChannelBufferTools.readHexString(buf, 16).substring(1);
            try {
                deviceId = getDataManager().getDeviceByImei(imei).getId();
            } catch(Exception error) {
                Log.warning("Unknown device - " + imei);
                return null;
            }

            // Send response
            if (channel != null) {
                channel.write(ChannelBuffers.wrappedBuffer(new byte[] { buf.readByte() }));
            }

        } else if (type == MSG_LOCATION) {

            return decodePosition(buf, false);

        } else if (type == MSG_HISTORY) {

            int count = buf.readUnsignedByte() & 0x0f;
            buf.readUnsignedShort(); // total count
            List<Position> positions = new LinkedList<Position>();

            for (int i = 0; i < count; i++) {
                positions.add(decodePosition(buf, true));
            }

            return positions;

        }

        return null;
    }

}
