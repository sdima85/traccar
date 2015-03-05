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

import java.net.SocketAddress;
import java.util.Calendar;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.Log;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

public class GoSafeProtocolDecoder extends BaseProtocolDecoder {

    public GoSafeProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private static final Pattern pattern = Pattern.compile(
            "\\*[^,]+," +
            "(\\d+)," +                         // IMEI
            "(\\d{2})(\\d{2})(\\d{2})" +        // Time
            "(\\d{2})(\\d{2})(\\d{2})," +       // Date
            ".*," +
            "GPS.([AV]);" +                     // Validity
            "\\d+;" +
            "([NS])(\\d+\\.\\d+);" +            // Latitude
            "([EW])(\\d+\\.\\d+);" +            // Longitude
            "(\\d+);" +                         // Speed
            "(\\d+);" +                         // Course
            "(\\d+);" +                         // Altitude
            "(\\d+\\.\\d+)" +                   // HDOP
            ".*");

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, SocketAddress remoteAddress, Object msg)
            throws Exception {

        String sentence = (String) msg;

        // Parse message
        Matcher parser = pattern.matcher(sentence);
        if (!parser.matches()) {
            return null;
        }

        // Create new position
        Position position = new Position();
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());

        Integer index = 1;

        // Get device by IMEI
        String imei = parser.group(index++);
        try {
            position.setDeviceId(getDataManager().getDeviceByImei(imei).getId());
        } catch(Exception error) {
            Log.warning("Unknown device - " + imei);
            return null;
        }
        
        // Date
        Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        time.clear();
        time.set(Calendar.HOUR_OF_DAY, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.DAY_OF_MONTH, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MONTH, Integer.valueOf(parser.group(index++)) - 1);
        time.set(Calendar.YEAR, 2000 + Integer.valueOf(parser.group(index++)));
        position.setTime(time.getTime());

        // Validity
        position.setValid(parser.group(index++).compareTo("A") == 0);

        // Latitude
        String hemisphere = parser.group(index++);
        Double latitude = Double.valueOf(parser.group(index++));
        if (hemisphere.compareTo("S") == 0) latitude = -latitude;
        position.setLatitude(latitude);

        // Longitude
        hemisphere = parser.group(index++);
        Double longitude = Double.valueOf(parser.group(index++));
        if (hemisphere.compareTo("W") == 0) longitude = -longitude;
        position.setLongitude(longitude);

        // Other
        position.setSpeed(Double.valueOf(parser.group(index++)));
        position.setCourse(Double.valueOf(parser.group(index++)));
        position.setAltitude(Double.valueOf(parser.group(index++)));
        extendedInfo.set("hdop", parser.group(index++));

        // Extended info
        position.setExtendedInfo(extendedInfo.toString());

        return position;
    }

}
